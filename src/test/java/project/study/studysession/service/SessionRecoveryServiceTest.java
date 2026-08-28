package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.common.NotFoundException;
import project.study.studysession.dto.SessionRecoveryResponse;

/** BY-455 세션 복구 판별·확인 — draft 확정/자동확정본 감지/재노출 방지/자정 집계를 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionRecoveryServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private SessionRecoveryService sessionRecoveryService;

    @Autowired
    private project.study.studysession.repository.StudySessionRepository studySessionRepository;

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
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private void insertDraft(Instant started, Instant reported, Instant lastSeen, int studySec, int focusSec) {
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
                userId,
                Timestamp.from(started),
                Timestamp.from(reported),
                Timestamp.from(lastSeen),
                studySec,
                focusSec);
    }

    private void insertSession(
            Instant started,
            Instant ended,
            Instant submissionStartedAt,
            int studySec,
            int focusSec,
            boolean autoFinalized,
            Instant acknowledgedAt) {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized, recovery_acknowledged_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(started, KST)),
                Timestamp.from(started),
                Timestamp.from(submissionStartedAt),
                Timestamp.from(ended),
                studySec,
                focusSec,
                autoFinalized,
                acknowledgedAt == null ? null : Timestamp.from(acknowledgedAt));
    }

    private Integer draftRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer sessionRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer acknowledgedRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ? AND recovery_acknowledged_at IS NOT NULL",
                Integer.class,
                userId);
    }

    @Test
    void draft가_있으면_확정하고_요약을_반환한다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant reported = started.plusSeconds(1800);
        insertDraft(started, reported, Instant.now().minusSeconds(600), 1800, 1700);

        SessionRecoveryResponse response = sessionRecoveryService.recover(userId);

        assertThat(response.studySec()).isEqualTo(1800);
        assertThat(response.focusSec()).isEqualTo(1700);
        assertThat(response.startedAt()).isEqualTo(started);
        assertThat(response.endedAt()).isEqualTo(reported);
        assertThat(response.statDate()).isEqualTo(LocalDate.ofInstant(started, KST));
        assertThat(draftRows()).isEqualTo(0);
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(acknowledgedRows()).isEqualTo(1);
    }

    @Test
    void draft가_없고_최근_자동확정본이_미확인이면_요약을_반환하고_확인_처리한다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant ended = started.plusSeconds(3600);
        insertSession(started, ended, started, 3600, 3400, true, null);

        SessionRecoveryResponse response = sessionRecoveryService.recover(userId);

        assertThat(response.studySec()).isEqualTo(3600);
        assertThat(response.focusSec()).isEqualTo(3400);
        assertThat(response.startedAt()).isEqualTo(started);
        assertThat(response.endedAt()).isEqualTo(ended);
        assertThat(acknowledgedRows()).isEqualTo(1);
    }

    @Test
    void 이미_확인된_자동확정본이면_404() {
        Instant started = Instant.now().minusSeconds(7200);
        insertSession(
                started,
                started.plusSeconds(3600),
                started,
                3600,
                3400,
                true,
                Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> sessionRecoveryService.recover(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 최근이_정상_종료_세션이면_404() {
        Instant started = Instant.now().minusSeconds(7200);
        insertSession(started, started.plusSeconds(3600), started, 3600, 3400, false, null);

        assertThatThrownBy(() -> sessionRecoveryService.recover(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 기록이_없으면_404() {
        assertThatThrownBy(() -> sessionRecoveryService.recover(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void draft가_정상_제출_세션의_잔여물이면_확정만_하고_404() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant ended = started.plusSeconds(3600);
        // 정상 제출로 이미 저장된 세션(autoFinalized=false)
        insertSession(started, ended, started, 3600, 3400, false, null);
        // 늦은 하트비트로 되살아난 잔여 draft(같은 started) — 정상 종료라 복구 대상이 아니다
        insertDraft(started, ended, Instant.now().minusSeconds(600), 3600, 3400);

        assertThatThrownBy(() -> sessionRecoveryService.recover(userId)).isInstanceOf(NotFoundException.class);
        assertThat(acknowledgedRows()).isEqualTo(0); // 정상 세션은 확인 처리되지 않는다
        assertThat(draftRows()).isEqualTo(0); // 잔여 draft는 정리된다
    }

    @Test
    void acknowledgeRecovery는_자동확정본만_확인_처리한다() {
        // 대체 레이스 방어: read와 update 사이 정상 제출이 그룹을 대체해도 정상 rows는 확인 처리되면 안 된다
        Instant started = Instant.now().minusSeconds(7200);
        insertSession(started, started.plusSeconds(3600), started, 3600, 3400, false, null);

        int claimed = studySessionRepository.acknowledgeRecovery(userId, started, Instant.now());

        assertThat(claimed).isEqualTo(0);
        assertThat(acknowledgedRows()).isEqualTo(0);
    }

    @Test
    void 한_번_확인한_뒤_두_번째_복구는_404() {
        Instant started = Instant.now().minusSeconds(7200);
        insertSession(started, started.plusSeconds(3600), started, 3600, 3400, true, null);

        sessionRecoveryService.recover(userId); // 첫 호출: 성공하며 확인 처리
        assertThatThrownBy(() -> sessionRecoveryService.recover(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 자정을_걸친_자동확정본은_한_건으로_집계한다() {
        Instant midnight = LocalDate.now(KST).atStartOfDay(KST).toInstant();
        Instant submissionStartedAt = midnight.minusSeconds(3600);
        // 조각1: 자정 이전(전날), 조각2: 자정 이후(오늘) — 둘이 submissionStartedAt을 공유
        insertSession(submissionStartedAt, midnight, submissionStartedAt, 3600, 3400, true, null);
        insertSession(midnight, midnight.plusSeconds(600), submissionStartedAt, 600, 500, true, null);

        SessionRecoveryResponse response = sessionRecoveryService.recover(userId);

        assertThat(response.startedAt()).isEqualTo(submissionStartedAt);
        assertThat(response.endedAt()).isEqualTo(midnight.plusSeconds(600));
        assertThat(response.studySec()).isEqualTo(4200);
        assertThat(response.focusSec()).isEqualTo(3900);
        assertThat(response.statDate()).isEqualTo(LocalDate.ofInstant(submissionStartedAt, KST));
        assertThat(acknowledgedRows()).isEqualTo(2);
    }
}
