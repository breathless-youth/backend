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

    // focusSec는 필수값이지만 서버는 이벤트 기반으로 재계산한다 — 0을 보내도 응답에는 계산값이 내려와야 한다
    private MockMvcTester.MockMvcRequestBuilder submitRequest(String userIdJson, String eventsJson) {
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "focusSec": 0, "events": %s}""".formatted(userIdJson, sessionStart, sessionEnd, eventsJson);
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
        assertThat(submitRequest(userId.toString(), "[]")).hasStatus(HttpStatus.CREATED);

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
        assertThat(submitRequest(userId.toString(), events)).hasStatus(HttpStatus.CREATED);

        assertThat(listRequest(today.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
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
        Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();
        String phoneEvent = eventJson("PHONE", midnight.minusSeconds(600), midnight.plusSeconds(600));
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "focusSec": 0, "events": [%s]}""".formatted(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), phoneEvent);

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

    @Test
    void date_없이_조회하면_400을_반환한다() {
        // 필수 파라미터 누락은 Spring 기본 처리라 본문 없이 상태 코드만 내려온다
        assertThat(mvc.get().uri("/api/study-sessions").param("userId", userId.toString()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void STOP_이벤트도_저장되고_집계에_잡힌다() {
        String stopEvent = eventJson("STOP", sessionStart.plusSeconds(600), sessionStart.plusSeconds(900));

        assertThat(submitRequest(userId.toString(), "[" + stopEvent + "]"))
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
}
