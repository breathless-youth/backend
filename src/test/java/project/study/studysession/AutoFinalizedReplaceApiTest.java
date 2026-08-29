package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** BY-447 대체 정책 — auto_finalized 세션은 잠정 기록이라 늦은 최종 제출이 대체하고, 클라 제출본은 불가침이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AutoFinalizedReplaceApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final Instant startedAt = Instant.parse("2026-08-20T03:00:00Z");

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    // findByLastSeenAtBefore(스케줄러 조회)가 전 유저 대상 풀스캔이라, 이 테스트가 남긴 draft가 커밋된 채
    // 남으면 다른 테스트 클래스(특히 ActiveSessionFinalizeTest)의 스캔 결과를 오염시킨다 — 매 테스트 뒤 정리한다.
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    /** 자동 확정된 세션 흉내 — 스케줄러가 만든 것과 같은 형태의 행을 직접 넣는다. */
    private void insertAutoFinalizedSession(Instant started, Instant ended, int studySec, int focusSec) {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(started, KST)),
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(ended),
                studySec,
                focusSec);
    }

    private void insertDraft(Instant started, Instant reported, int studySec, int focusSec) {
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
                userId,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(reported),
                java.sql.Timestamp.from(reported),
                studySec,
                focusSec);
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

    private Integer sessionRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer draftRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 자동_확정본은_늦은_최종_제출로_대체된다() {
        insertAutoFinalizedSession(startedAt, startedAt.plusSeconds(1800), 1800, 1700);

        MvcTestResult result = submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400);
        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(3600));

        assertThat(sessionRows()).isEqualTo(1);
        Boolean autoFinalized = jdbcTemplate.queryForObject(
                "SELECT auto_finalized FROM study_session WHERE user_id = ?", Boolean.class, userId);
        assertThat(autoFinalized).isFalse();
    }

    @Test
    void 클라_제출본은_재제출이_와도_대체되지_않는다() {
        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        MvcTestResult replay = submit(startedAt, startedAt.plusSeconds(7000), 7000, 6500);
        assertThat(replay)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(3600));

        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void 최종_제출이_성공하면_같은_세션의_draft가_삭제된다() {
        insertDraft(startedAt, startedAt.plusSeconds(1800), 1800, 1700);

        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 멱등_반환_경로에서는_draft를_지우지_않는다() {
        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);
        // 제출 후 뒤늦은 하트비트가 draft를 되살린 상황 — 재제출은 멱등 반환만 하고 draft는 스케줄러가 정리한다
        insertDraft(startedAt, startedAt.plusSeconds(3900), 3900, 3600);

        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        assertThat(draftRows()).isEqualTo(1);
    }
}
