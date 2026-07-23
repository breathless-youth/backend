package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

    private Long userId;

    // 검증 규칙(미래 시각 금지)을 지키도록 항상 최근 과거의 세션을 만든다
    private final Instant sessionStart = Instant.now().minusSeconds(7200).truncatedTo(ChronoUnit.SECONDS);
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
                .hasPathSatisfying("$.id", id -> assertThat(id).isNotNull())
                .hasPathSatisfying("$.userId", uid -> assertThat(uid).isEqualTo(userId.intValue()))
                .hasPathSatisfying("$.sessionSec", sec -> assertThat(sec).isEqualTo(7200))
                .hasPathSatisfying("$.focusSec", sec -> assertThat(sec).isEqualTo(6600))
                .hasPathSatisfying("$.focusRate", rate -> assertThat(rate).isEqualTo(91.7));
        long sessionId = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
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

    @Test
    void 기간으로_세션_목록을_조회한다() {
        assertThat(submitRequest(userId.toString(), "[]")).hasStatus(HttpStatus.CREATED);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(mvc.get()
                        .uri("/api/study-sessions")
                        .param("userId", userId.toString())
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.plusDays(1).toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(1))
                .extractingPath("$[0].sessionSec")
                .isEqualTo(7200);
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
