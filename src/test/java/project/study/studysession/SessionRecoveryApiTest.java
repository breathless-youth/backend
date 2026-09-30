package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.SessionRecoveryResponse;
import tools.jackson.databind.ObjectMapper;

/** BY-455 복구 판별·확인 API — HTTP 계약(200 요약 / 404 / draft 확정)을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionRecoveryApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void seed() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    @Test
    void 자동확정본을_요약으로_반환하고_확인_처리한다() throws Exception {
        Instant started = Instant.parse("2026-08-20T03:00:00Z");
        Instant ended = started.plusSeconds(3600);
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(started, KST)),
                Timestamp.from(started),
                Timestamp.from(started),
                Timestamp.from(ended),
                3600,
                3400);

        MvcTestResult result = mvc.post()
                .uri("/api/study-sessions/recovery")
                .param("userId", String.valueOf(userId))
                .exchange();

        assertThat(result).hasStatusOk();
        SessionRecoveryResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), SessionRecoveryResponse.class);
        assertThat(body.studySec()).isEqualTo(3600);
        assertThat(body.focusSec()).isEqualTo(3400);
        Integer acknowledged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ? AND recovery_acknowledged_at IS NOT NULL",
                Integer.class,
                userId);
        assertThat(acknowledged).isEqualTo(1);
    }

    @Test
    void 복구할_세션이_없으면_404() {
        assertThat(mvc.post()
                        .uri("/api/study-sessions/recovery")
                        .param("userId", String.valueOf(userId))
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void draft가_있으면_확정하고_요약을_반환한다() throws Exception {
        Instant started = Instant.parse("2026-08-20T03:00:00Z");
        Instant reported = started.plusSeconds(1800);
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
                userId,
                Timestamp.from(started),
                Timestamp.from(reported),
                Timestamp.from(Instant.now().minusSeconds(600)),
                1800,
                1700);

        MvcTestResult result = mvc.post()
                .uri("/api/study-sessions/recovery")
                .param("userId", String.valueOf(userId))
                .exchange();

        assertThat(result).hasStatusOk();
        SessionRecoveryResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), SessionRecoveryResponse.class);
        assertThat(body.studySec()).isEqualTo(1800);
        Integer draftRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(draftRows).isEqualTo(0);
        Integer sessionRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(sessionRows).isEqualTo(1);
    }
}
