package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

/** BY-447 진행 스냅샷 보고 API — 30초마다 오는 누적 스냅샷의 UPSERT·역순 무시·검증을 다룬다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActiveSessionSnapshotApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    // 검증(미래 시각 금지)이 실제 시계를 쓰므로 항상 과거인 시각을 기준으로 잡는다
    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult report(Long uid, Instant started, Instant reported, int studySec, int focusSec) {
        return report(uid, started, reported, studySec, focusSec, "[]");
    }

    private MvcTestResult report(
            Long uid, Instant started, Instant reported, int studySec, int focusSec, String eventsJson) {
        String body = """
				{"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s}""".formatted(uid, started, reported, studySec, focusSec, eventsJson);
        return mvc.put()
                .uri("/api/study-sessions/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private Integer draftRows(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    private Integer draftStudySec(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT study_sec FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void 첫_스냅샷은_draft를_만든다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(draftRows(userId)).isEqualTo(1);
    }

    @Test
    void 두번째_스냅샷은_행을_늘리지_않고_덮어쓴다() {
        report(userId, startedAt, startedAt.plusSeconds(30), 30, 30);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 60, 55)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftRows(userId)).isEqualTo(1);
        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 저장된_것보다_과거_reportedAt은_조용히_무시된다() {
        report(userId, startedAt, startedAt.plusSeconds(60), 60, 55);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 이벤트_스냅샷이_jsonb로_저장된다() {
        String events = """
				[{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(10), startedAt.plusSeconds(20));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 20, 10, events))
                .hasStatus(HttpStatus.NO_CONTENT);

        String stored = jdbcTemplate.queryForObject(
                "SELECT events::text FROM active_study_session WHERE user_id = ?", String.class, userId);
        assertThat(stored).contains("PHONE");
    }

    @Test
    void reportedAt이_startedAt_이전이면_400이다() {
        assertThat(report(userId, startedAt, startedAt, 0, 0)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(draftRows(userId)).isEqualTo(0);
    }

    @Test
    void focusSec이_studySec을_넘으면_400이다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 40)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이벤트가_세션_구간_밖이면_400이다() {
        String events = """
				[{"status":"AWAY","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(50), startedAt.plusSeconds(90));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 20, events))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 존재하지_않는_유저는_404다() {
        assertThat(report(999_999L, startedAt, startedAt.plusSeconds(30), 30, 30))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 필수값_누락은_400이다() {
        String body = """
				{"userId": %d, "startedAt": "%s"}""".formatted(userId, startedAt);
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
