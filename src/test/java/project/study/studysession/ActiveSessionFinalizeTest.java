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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.scheduler.ActiveSessionFinalizeScheduler;
import project.study.studysession.service.ActiveStudySessionService;

/** BY-447 무응답 draft 자동 확정 — 스케줄러가 호출하는 서비스 메서드를 직접 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveSessionFinalizeTest {

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

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

    // findByLastSeenAtBefore가 전 유저 대상 풀스캔이라, 여기서 만든 draft가 커밋된 채 남으면
    // 다른 테스트 클래스의 같은 쿼리 결과를 오염시킨다(특히 신선한_draft 테스트는 확정/폐기 호출이
    // 없어 그대로 남는다) — 매 테스트 뒤 이 유저의 draft를 직접 정리해 격리한다.
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private Long insertDraft(
            Instant started, Instant reported, Instant lastSeen, int studySec, int focusSec, String events) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
                Long.class,
                userId,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(reported),
                java.sql.Timestamp.from(lastSeen),
                studySec,
                focusSec,
                events);
    }

    private Instant staleLastSeen() {
        return Instant.now().minusSeconds(600); // 유예 5분보다 오래됨
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
    void 무응답_draft가_세션으로_확정되고_draft는_삭제된다() {
        String events = """
                [{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(100), startedAt.plusSeconds(200));
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, events);

        assertThat(activeStudySessionService.findStaleDraftIds()).contains(draftId);
        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        assertThat(draftRows()).isEqualTo(0);
        Boolean autoFinalized = jdbcTemplate.queryForObject(
                "SELECT auto_finalized FROM study_session WHERE user_id = ?", Boolean.class, userId);
        assertThat(autoFinalized).isTrue();
        // jsonb 이벤트가 status_event 행으로 복원된다
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM status_event e JOIN study_session s ON e.session_id = s.id"
                        + " WHERE s.user_id = ?",
                Integer.class,
                userId);
        assertThat(eventRows).isEqualTo(1);
    }

    @Test
    void 자정을_걸친_draft는_두_조각으로_확정된다() {
        Instant midnight = LocalDate.now(KST).atStartOfDay(KST).toInstant();
        Long draftId =
                insertDraft(midnight.minusSeconds(3600), midnight.plusSeconds(600), staleLastSeen(), 4200, 4000, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(2);
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 신선한_draft는_확정_대상이_아니다() {
        insertDraft(startedAt, startedAt.plusSeconds(60), Instant.now(), 60, 60, "[]");

        assertThat(activeStudySessionService.findStaleDraftIds()).isEmpty();
    }

    @Test
    void 이미_클라가_제출한_세션의_잔여_draft는_세션을_건드리지_않고_삭제만_된다() {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, false)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, KST)),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(3600)),
                3600,
                3400);
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(3600); // 클라 제출본 불가침
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 자동_확정본이_있는_세션의_draft가_재확정되면_더_완전한_기록으로_대체된다() {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, KST)),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(1800)),
                1800,
                1700);
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(3600), staleLastSeen(), 3600, 3400, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(3600);
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 이미_처리된_draftId는_조용히_넘어간다() {
        activeStudySessionService.finalizeDraft(999_999L);
        assertThat(sessionRows()).isEqualTo(0);
    }

    @Test
    void discardDraft는_세션_없이_draft만_지운다() {
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");

        activeStudySessionService.discardDraft(draftId);

        assertThat(sessionRows()).isEqualTo(0);
        assertThat(draftRows()).isEqualTo(0);
    }

    // 최종 리뷰 Important 2 — 영구 실패(검증 불능)만 폐기하고, 하나가 실패해도 나머지 draft는 계속 처리된다는 것을
    // 스케줄러 빈의 finalizeStaleDrafts()를 직접 호출해 검증한다(스케줄러 빈은 session-finalize.enabled=false로
    // 컨텍스트에 없으므로 여기서 직접 생성한다).
    @Test
    void 검증_불능_draft는_폐기되고_정상_draft는_확정되며_루프는_중단되지_않는다() {
        Instant invalidStartedAt = startedAt.plusSeconds(10_000);
        insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");
        // reportedAt(=확정 시 endedAt)이 startedAt보다 이전 — 하트비트 검증이 막았어야 할 영구 실패
        insertDraft(invalidStartedAt, invalidStartedAt.minusSeconds(10), staleLastSeen(), 100, 100, "[]");

        ActiveSessionFinalizeScheduler scheduler = new ActiveSessionFinalizeScheduler(activeStudySessionService);
        scheduler.finalizeStaleDrafts();

        assertThat(sessionRows()).isEqualTo(1); // 정상 draft만 세션으로 확정
        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(1800);
        assertThat(draftRows()).isEqualTo(0); // 정상 draft는 확정으로, 검증 불능 draft는 폐기로 둘 다 사라짐
    }
}
