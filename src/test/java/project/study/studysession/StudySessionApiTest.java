package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;
    private final LocalDate today = LocalDate.now(KST);
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(int studySec, int focusSec, String eventsJson) {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s}""".formatted(userId, sessionStart, sessionEnd, studySec, focusSec, eventsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String eventJson(String status, Instant startedAt, Instant endedAt) {
        return """
                {"status": "%s", "startedAt": "%s", "endedAt": "%s"}""".formatted(status, startedAt, endedAt);
    }

    @Test
    void 세션을_제출하면_요청의_총공부시간과_순공시간이_그대로_저장된다() {
        String phoneEvent = eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200));

        MvcTestResult result = submitRequest(7200, 6600, "[" + phoneEvent + "]").exchange();
        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(1))
                .hasPathSatisfying("$[0].id", id -> assertThat(id).isNotNull())
                .hasPathSatisfying("$[0].userId", uid -> assertThat(uid).isEqualTo(userId.intValue()))
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(7200))
                .hasPathSatisfying("$[0].focusSec", sec -> assertThat(sec).isEqualTo(6600))
                .hasPathSatisfying("$[0].focusRate", rate -> assertThat(rate).isEqualTo(91.7));
        long sessionId = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();

        List<String> savedStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM status_event WHERE session_id = ?", String.class, sessionId);
        assertThat(savedStatuses).containsExactly("PHONE");
    }

    private MockMvcTester.MockMvcRequestBuilder listRequest(LocalDate date) {
        return mvc.get().uri("/api/stats").param("userId", userId.toString()).param("date", date.toString());
    }

    @Test
    void 날짜로_그날의_세션_목록과_개수를_조회한다() {
        assertThat(submitRequest(7200, 7200, "[]")).hasStatus(HttpStatus.CREATED);
        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.sessions.length()", length -> assertThat(length).isEqualTo(1))
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .extractingPath("$.sessions[0].studySec")
                .isEqualTo(7200);
    }

    @Test
    void 목록_응답에_그_달_공부한_날짜_목록이_담긴다() {
        assertThat(submitRequest(7200, 7200, "[]")).hasStatus(HttpStatus.CREATED);

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.studiedDatesInMonth",
                        dates -> assertThat(dates)
                                .asInstanceOf(InstanceOfAssertFactories.LIST)
                                .contains(today.minusDays(1).toString()));
    }

    @Test
    void 목록_응답에_그날_합계와_상태별_이벤트_건수가_내려온다() {
        String events = "["
                + eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200))
                + ","
                + eventJson("AWAY", sessionStart.plusSeconds(1800), sessionStart.plusSeconds(2100))
                + "]";
        assertThat(submitRequest(7200, 6300, events)).hasStatus(HttpStatus.CREATED);
        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(7200))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(6300))
                .hasPathSatisfying("$.longestFocusSec", v -> assertThat(v).isEqualTo(5100))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(87.5))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalEventCounts.AWAY", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.totalEventCounts.DEVICE", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.sessions[0].eventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.sessions[0].eventCounts.AWAY", v -> assertThat(v).isEqualTo(1));
    }

    private void submitCrossMidnightSession() {
        Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();
        String phoneEvent = eventJson("PHONE", midnight.minusSeconds(600), midnight.plusSeconds(600));
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6000, "events": [%s]}""".formatted(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), phoneEvent);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].statDate",
                        d -> assertThat(d).isEqualTo(today.minusDays(2).toString()))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$[0].focusRate", v -> assertThat(v).isEqualTo(83.3))
                .hasPathSatisfying(
                        "$[1].statDate",
                        d -> assertThat(d).isEqualTo(today.minusDays(1).toString()))
                .hasPathSatisfying("$[1].studySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[1].focusSec", v -> assertThat(v).isEqualTo(3000));
    }

    @Test
    void 자정을_넘는_세션은_두_개로_분할_저장된다() {
        submitCrossMidnightSession();
        Integer sessionRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM status_event e JOIN study_session s ON e.session_id = s.id WHERE s.user_id = ?",
                Integer.class,
                userId);
        assertThat(sessionRows).isEqualTo(2);
        assertThat(eventRows).isEqualTo(2);
    }

    @Test
    void 분할된_세션은_각_날짜_조회에_한_조각씩_잡힌다() {
        submitCrossMidnightSession();
        assertThat(listRequest(today.minusDays(2)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(83.3))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(1));

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 기록_없는_날짜는_빈_래퍼가_내려온다() {
        assertThat(listRequest(LocalDate.of(2000, 1, 1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.longestFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(0.0))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.studiedDatesInMonth.length()", v -> assertThat(v).isEqualTo(0));
    }

    private MockMvcTester.MockMvcRequestBuilder streakRequest() {
        return mvc.get().uri("/api/stats/streak").param("userId", userId.toString());
    }

    @Test
    void 어제_세션을_제출하면_스트릭이_1이다() {
        assertThat(submitRequest(7200, 7200, "[]")).hasStatus(HttpStatus.CREATED);
        assertThat(streakRequest())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 같은_날_여러_세션과_자정_분할은_스트릭에_하루씩만_잡힌다() {
        submitCrossMidnightSession();
        assertThat(submitRequest(7200, 7200, "[]")).hasStatus(HttpStatus.CREATED);
        assertThat(streakRequest())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 기록_없는_유저는_스트릭이_0이다() {
        assertThat(streakRequest())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void date_없이_조회하면_400을_반환한다() {
        assertThat(mvc.get().uri("/api/stats").param("userId", userId.toString()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void PAUSE_이벤트도_저장되고_집계에_잡힌다() {
        String pauseEvent = eventJson("PAUSE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(900));

        assertThat(submitRequest(6900, 6900, "[" + pauseEvent + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(6900))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(6900));

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.totalEventCounts.PAUSE", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void studySec_없이_제출하면_400을_반환한다() {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "focusSec": 7200, "events": []}""".formatted(userId, sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("studySec"));
    }

    @Test
    void focusSec_없이_제출하면_400을_반환한다() {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": 7200, "events": []}""".formatted(userId, sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("focusSec"));
    }

    @Test
    void 겹치는_이벤트가_있으면_400을_반환한다() {
        String events = "["
                + eventJson("DEVICE", sessionStart, sessionStart.plusSeconds(600))
                + ","
                + eventJson("PHONE", sessionStart.plusSeconds(300), sessionStart.plusSeconds(900))
                + "]";

        assertThat(submitRequest(7200, 6600, events))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message).isNotNull());
    }

    @Test
    void 순공_시간이_총공부시간을_초과하면_400을_반환한다() {
        assertThat(submitRequest(7200, 7201, "[]"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("순공 시간"));
    }

    @Test
    void 순공_시간이_음수면_400을_반환한다() {
        assertThat(submitRequest(7200, -1, "[]"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("순공 시간"));
    }

    @Test
    void 총공부시간이_PAUSE를_제외한_시간을_초과하면_400을_반환한다() {
        String pauseEvent = eventJson("PAUSE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(900));

        assertThat(submitRequest(6901, 0, "[" + pauseEvent + "]"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("총 공부 시간"));
    }

    @Test
    void 존재하지_않는_사용자면_404를_반환한다() {
        String body = """
                {"userId": 999999999, "startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 7200, "events": []}""".formatted(sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
