package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    // 기준 날짜를 한 번만 읽어 고정 — 테스트 도중 KST 자정이 지나도 입력과 기대값이 같은 기준을 쓴다
    private final LocalDate today = LocalDate.now(KST);

    // 미래 시각 검증과 자정 분할을 모두 피하도록 어제 KST 낮 12~14시의 세션을 만든다
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

    // focusSec는 앱 제출값이 그대로 저장된다 — 응답에도 보낸 값이 그대로 내려와야 한다
    private MockMvcTester.MockMvcRequestBuilder submitRequest(String userIdJson, int focusSec, String eventsJson) {
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "focusSec": %d, "events": %s}""".formatted(userIdJson, sessionStart, sessionEnd, focusSec, eventsJson);
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
    void 세션을_제출하면_요청의_순공_시간이_그대로_저장된다() {
        String phoneEvent = eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200));

        MvcTestResult result =
                submitRequest(userId.toString(), 6600, "[" + phoneEvent + "]").exchange();
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

        // 단건 조회 API가 제거되어 이벤트 저장 여부는 DB에서 직접 확인한다
        List<String> savedStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM status_event WHERE session_id = ?", String.class, sessionId);
        assertThat(savedStatuses).containsExactly("PHONE");
    }

    private MockMvcTester.MockMvcRequestBuilder listRequest(LocalDate date) {
        return mvc.get()
                .uri("/api/study-sessions")
                .param("userId", userId.toString())
                .param("date", date.toString());
    }

    @Test
    void 날짜로_그날의_세션_목록과_개수를_조회한다() {
        assertThat(submitRequest(userId.toString(), 7200, "[]")).hasStatus(HttpStatus.CREATED);

        // 세션은 어제 KST 낮 12~14시 — statDate가 어제이므로 date=어제 조회에 잡혀야 한다
        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.sessions.length()", length -> assertThat(length).isEqualTo(1))
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .extractingPath("$.sessions[0].sessionSec")
                .isEqualTo(7200);
    }

    @Test
    void 목록_응답에_그날_합계와_상태별_이벤트_건수가_내려온다() {
        String events = "["
                + eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200))
                + ","
                + eventJson("AWAY", sessionStart.plusSeconds(1800), sessionStart.plusSeconds(2100))
                + "]";
        assertThat(submitRequest(userId.toString(), 6300, events)).hasStatus(HttpStatus.CREATED);

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(7200))
                // 제출한 focusSec 6300이 그대로 합계에 잡힌다 → 집중률 87.5%
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(6300))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(87.5))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.eventCounts.AWAY", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.eventCounts.DEVICE", v -> assertThat(v).isEqualTo(0));
    }

    // 어제 KST 자정을 걸치는 세션 (그저께 23시 ~ 어제 01시), PHONE 이벤트가 자정에 걸침
    // 제출한 focusSec 6000이 조각 길이(3600:3600)에 비례해 3000씩 배분된다
    private void submitCrossMidnightSession() {
        Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();
        String phoneEvent = eventJson("PHONE", midnight.minusSeconds(600), midnight.plusSeconds(600));
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "focusSec": 6000, "events": [%s]}""".formatted(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), phoneEvent);

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
                .hasPathSatisfying("$[0].sessionSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$[0].focusRate", v -> assertThat(v).isEqualTo(83.3))
                .hasPathSatisfying(
                        "$[1].statDate",
                        d -> assertThat(d).isEqualTo(today.minusDays(1).toString()))
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
    void 분할된_세션은_각_날짜_조회에_한_조각씩_잡힌다() {
        submitCrossMidnightSession();

        // 그저께 조회: 23~00시 조각 + 자정에 걸친 이벤트의 앞 조각 1건
        assertThat(listRequest(today.minusDays(2)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(83.3))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(1));

        // 어제 조회: 00~01시 조각 + 이벤트의 뒤 조각 1건
        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 기록_없는_날짜는_빈_래퍼가_내려온다() {
        assertThat(listRequest(LocalDate.of(2000, 1, 1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalSessionSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(0.0))
                .hasPathSatisfying("$.eventCounts.PHONE", v -> assertThat(v).isEqualTo(0));
    }

    private MockMvcTester.MockMvcRequestBuilder streakRequest() {
        return mvc.get().uri("/api/study-sessions/streak").param("userId", userId.toString());
    }

    @Test
    void 어제_세션을_제출하면_스트릭이_1이다() {
        // 세션은 어제 KST 낮 12~14시 — 오늘 기록이 없어도 어제까지 이어진 스트릭은 유지된다
        assertThat(submitRequest(userId.toString(), 7200, "[]")).hasStatus(HttpStatus.CREATED);

        assertThat(streakRequest())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 같은_날_여러_세션과_자정_분할은_스트릭에_하루씩만_잡힌다() {
        // 그저께~어제 자정 분할(날짜 2개) + 어제 낮 세션(중복 날짜) → 실제 JPQL의 distinct·정렬 검증
        submitCrossMidnightSession();
        assertThat(submitRequest(userId.toString(), 7200, "[]")).hasStatus(HttpStatus.CREATED);

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
        // 필수 파라미터 누락은 Spring 기본 처리라 본문 없이 상태 코드만 내려온다
        assertThat(mvc.get().uri("/api/study-sessions").param("userId", userId.toString()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void STOP_이벤트도_저장되고_집계에_잡힌다() {
        String stopEvent = eventJson("STOP", sessionStart.plusSeconds(600), sessionStart.plusSeconds(900));

        assertThat(submitRequest(userId.toString(), 6900, "[" + stopEvent + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(6900));

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.eventCounts.STOP", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void focusSec_없이_제출하면_400을_반환한다() {
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "events": []}""".formatted(userId, sessionStart, sessionEnd);

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

        assertThat(submitRequest(userId.toString(), 6600, events))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message).isNotNull());
    }

    @Test
    void 순공_시간이_총시간을_초과하면_400을_반환한다() {
        assertThat(submitRequest(userId.toString(), 7201, "[]"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("순공 시간"));
    }

    @Test
    void 순공_시간이_음수면_400을_반환한다() {
        assertThat(submitRequest(userId.toString(), -1, "[]"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("순공 시간"));
    }

    @Test
    void 필수_값이_없으면_400을_반환한다() {
        assertThat(submitRequest("null", 7200, "[]")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 존재하지_않는_사용자면_404를_반환한다() {
        assertThat(submitRequest("999999999", 7200, "[]")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
