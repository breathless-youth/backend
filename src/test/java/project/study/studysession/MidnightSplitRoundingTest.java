package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * BY-471 자정 분할 초 손실 회귀 — 세션이 KST 자정을 넘고 studySec이 세션길이와 정확히 같아도(경계값)
 * 생성되어야 하고, 분할된 조각들의 study_sec 합이 원본과 일치해야 한다.
 *
 * <p>고정된 과거 시각(2020)을 써서 실행 시각과 무관하게 항상 자정 크로싱을 재현한다 — 기존 버그는
 * `Instant.now()`를 써서 KST 01~02시에만 터졌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MidnightSplitRoundingTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM study_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private MvcTestResult submit(Instant started, Instant ended, int studySec, int focusSec) {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(userId, started, ended, studySec, focusSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void 자정을_넘고_studySec이_세션길이와_같아도_생성되고_조각합이_원본과_일치한다() {
        // 2020-01-01 23:02:03.3 KST → 2020-01-02 00:02:03.3 KST (자정 크로싱, sub-second 시작)
        Instant started = Instant.parse("2020-01-01T14:02:03.300Z");
        Instant ended = started.plusSeconds(3600); // studySec == 세션길이(3600) 경계값

        // 수정 전에는 자정 분할의 이중 내림으로 totalStudyActiveSec=3599가 되어 400이 났다
        assertThat(submit(started, ended, 3600, 3400)).hasStatus(HttpStatus.CREATED);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isEqualTo(2); // 자정에서 두 조각으로 분할

        Integer studySum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(study_sec), 0) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySum).isEqualTo(3600); // 조각 합 == 원본 (1초 손실 없음)

        // 각 조각의 공부시간이 제 구간 길이를 넘지 않는다 (자기일관성)
        Integer overflow = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?"
                        + " AND study_sec > CEIL(EXTRACT(EPOCH FROM (ended_at - started_at)))",
                Integer.class,
                userId);
        assertThat(overflow).isZero();
    }
}
