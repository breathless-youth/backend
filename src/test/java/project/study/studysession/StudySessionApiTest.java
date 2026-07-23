package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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
// 인증은 MVP 제외 (ADR-0004) — 재도입 시 @WithMockUser 등으로 인증 컨텍스트 추가 필요
class StudySessionApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    // 미래 시각 검증과 자정 분할을 모두 피하도록 어제 KST 낮 12~14시의 세션을 만든다
    private final Instant sessionStart =
            LocalDate.now(KST).minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(String userIdJson, String eventsJson) {
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "events": %s}""".formatted(userIdJson, sessionStart, sessionEnd, eventsJson);
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
    void 세션을_제출하면_집중_시간이_계산되어_저장된다() {
        String phoneEvent = eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200));

        MvcTestResult result =
                submitRequest(userId.toString(), "[" + phoneEvent + "]").exchange();
        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(1))
                .hasPathSatisfying("$[0].id", id -> assertThat(id).isNotNull())
                .hasPathSatisfying("$[0].userId", uid -> assertThat(uid).isEqualTo(userId.intValue()))
                .hasPathSatisfying("$[0].sessionSec", sec -> assertThat(sec).isEqualTo(7200))
                .hasPathSatisfying("$[0].focusSec", sec -> assertThat(sec).isEqualTo(6600))
                .hasPathSatisfying("$[0].focusRate", rate -> assertThat(rate).isEqualTo(91.7));
        long sessionId = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();

        assertThat(mvc.get().uri("/api/study-sessions/{id}", sessionId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.events.length()", length -> assertThat(length).isEqualTo(1))
                .extractingPath("$.events[0].status")
                .isEqualTo("PHONE");
    }

    private MockMvcTester.MockMvcRequestBuilder listRequest(LocalDate from, LocalDate to) {
        return mvc.get()
                .uri("/api/study-sessions")
                .param("userId", userId.toString())
                .param("from", from.toString())
                .param("to", to.toString());
    }

    @Test
    void 기간으로_세션_목록을_조회한다() {
        assertThat(submitRequest(userId.toString(), "[]")).hasStatus(HttpStatus.CREATED);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(listRequest(today.minusDays(1), today.plusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.sessions.length()", length -> assertThat(length).isEqualTo(1))
                .extractingPath("$.sessions[0].sessionSec")
                .isEqualTo(7200);
    }

    @Test
    void 목록_응답에_기간_합계와_상태별_이벤트_건수가_내려온다() {
        String events = "["
                + eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200))
                + ","
                + eventJson("AWAY", sessionStart.plusSeconds(1800), sessionStart.plusSeconds(2100))
                + "]";
        assertThat(submitRequest(userId.toString(), events)).hasStatus(HttpStatus.CREATED);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(listRequest(today.minusDays(1), today.plusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(7200))
                // 7200 - 600(PHONE) - 300(AWAY) = 6300 → 집중률 87.5%
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(6300))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(87.5))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.eventCounts.AWAY", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.eventCounts.DEVICE", v -> assertThat(v).isEqualTo(0));
    }

    // 어제 KST 자정을 걸치는 세션 (그저께 23시 ~ 어제 01시), PHONE 이벤트가 자정에 걸침
    private void submitCrossMidnightSession() {
        Instant midnight = LocalDate.now(KST).minusDays(1).atStartOfDay(KST).toInstant();
        String phoneEvent = eventJson("PHONE", midnight.minusSeconds(600), midnight.plusSeconds(600));
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "events": [%s]}""".formatted(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), phoneEvent);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].statDate",
                        d -> assertThat(d)
                                .isEqualTo(LocalDate.now(KST).minusDays(2).toString()))
                .hasPathSatisfying("$[0].sessionSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$[0].focusRate", v -> assertThat(v).isEqualTo(83.3))
                .hasPathSatisfying(
                        "$[1].statDate",
                        d -> assertThat(d)
                                .isEqualTo(LocalDate.now(KST).minusDays(1).toString()))
                .hasPathSatisfying("$[1].sessionSec", v -> assertThat(v).isEqualTo(3600))
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
    void 분할된_이벤트는_기간_조회에서_2건으로_집계된다() {
        submitCrossMidnightSession();

        LocalDate today = LocalDate.now(KST);
        assertThat(listRequest(today.minusDays(2), today))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(7200))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(6000))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(83.3))
                // 자정에 걸친 이벤트 1건이 날짜별 세션에 나뉘어 2건으로 집계된다 (의도된 동작)
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 기록_없는_기간은_빈_래퍼가_내려온다() {
        assertThat(listRequest(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(0.0))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 겹치는_이벤트가_있으면_400을_반환한다() {
        String events = "["
                + eventJson("DEVICE", sessionStart, sessionStart.plusSeconds(600))
                + ","
                + eventJson("PHONE", sessionStart.plusSeconds(300), sessionStart.plusSeconds(900))
                + "]";

        assertThat(submitRequest(userId.toString(), events))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message).isNotNull());
    }

    @Test
    void 필수_값이_없으면_400을_반환한다() {
        assertThat(submitRequest("null", "[]")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 존재하지_않는_사용자면_404를_반환한다() {
        assertThat(submitRequest("999999999", "[]")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 존재하지_않는_세션_조회는_404를_반환한다() {
        assertThat(mvc.get().uri("/api/study-sessions/{id}", 999999999L)).hasStatus(HttpStatus.NOT_FOUND);
    }
}
