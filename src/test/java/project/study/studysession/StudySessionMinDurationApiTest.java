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
import project.study.TestcontainersConfiguration;

/** 10분 미만 세션은 저장하지 않는다 — 저장이 안 되므로 스트릭·통계에도 잡히지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 인증은 MVP 제외 (ADR-0004) — 재도입 시 @WithMockUser 등으로 인증 컨텍스트 추가 필요
class StudySessionMinDurationApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    // 미래 시각 검증과 자정 분할을 모두 피하도록 어제 KST 낮 12시 시작 세션을 쓴다
    private final Instant sessionStart =
            LocalDate.now(KST).minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(int durationSec) {
        String body =
                """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(userId, sessionStart, sessionStart.plusSeconds(durationSec), durationSec, durationSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void 십분_미만_세션은_400이고_저장도_스트릭_반영도_되지_않는다() {
        assertThat(submitRequest(599))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("10분 이상"));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isEqualTo(0);

        // 어제 세션이 저장되지 않았으므로 스트릭도 0이어야 한다
        assertThat(mvc.get().uri("/api/stats/streak").param("userId", userId.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 정확히_십분_세션은_저장되고_스트릭에_잡힌다() {
        assertThat(submitRequest(600)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get().uri("/api/stats/streak").param("userId", userId.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(1));
    }
}
