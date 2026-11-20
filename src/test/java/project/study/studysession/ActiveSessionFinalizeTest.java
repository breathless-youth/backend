package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.room.support.MutableClock;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.scheduler.ActiveSessionFinalizeScheduler;
import project.study.studysession.service.ActiveStudySessionService;

/** BY-447 무응답 draft 자동 확정 — 스케줄러가 호출하는 서비스 메서드를 직접 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveSessionFinalizeTest {

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        @Primary
        MutableClock frozenClock() {
            return MutableClock.at(Instant.now());
        }
    }

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final Instant startedAt = Instant.parse("2026-08-20T03:00:00Z");

    @BeforeEach
    void createUser() {
        // 경계 테스트는 유예 시간과 정확히 같은 간격을 넣으므로, 테스트 도중 시계가 흐르면 판정이 바뀐다.
        // PostgreSQL이 마이크로초까지만 저장해서 그 단위로 잘라 둔다.
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    // findStaleDrafts가 전 유저 대상 풀스캔이라, 여기서 만든 draft가 커밋된 채 남으면
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
    void draft의_관찰_기록이_확정_세션으로_옮겨진다() {
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");
        jdbcTemplate.update(
                "UPDATE active_study_session SET observations = ?::jsonb WHERE id = ?",
                """
                [{"type":"YAWN","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(100), startedAt.plusSeconds(104)),
                draftId);

        activeStudySessionService.finalizeDraft(draftId);

        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_observation o JOIN study_session s ON o.session_id = s.id"
                        + " WHERE s.user_id = ? AND o.type = 'YAWN'",
                Integer.class,
                userId);
        assertThat(observationRows).isEqualTo(1);
    }

    @Test
    void TIMER_draft는_TIMER_세션으로_자동_확정된다() {
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1800, "[]");
        jdbcTemplate.update("UPDATE active_study_session SET source = 'TIMER' WHERE id = ?", draftId);

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(jdbcTemplate.queryForList(
                        "SELECT source FROM study_session WHERE user_id = ?", String.class, userId))
                .containsExactly("TIMER");
        assertThat(draftRows()).isEqualTo(0);
    }

    private Long insertDraftWithSource(String source, Instant started, Instant reported, Instant lastSeen) {
        Long draftId = insertDraft(started, reported, lastSeen, 600, 600, "[]");
        jdbcTemplate.update("UPDATE active_study_session SET source = ? WHERE id = ?", source, draftId);
        return draftId;
    }

    @Test
    void MEASURED_draft는_5분_넘게_보고가_없으면_확정_대상이다() {
        Instant now = Instant.now();
        Long draftId = insertDraftWithSource(
                "MEASURED", now.minusSeconds(1200), now.minusSeconds(600), now.minusSeconds(6 * 60));

        assertThat(activeStudySessionService.findStaleDraftIds()).contains(draftId);
    }

    @Test
    void TIMER_draft는_5분이_지나도_2시간_전이면_확정_대상이_아니다() {
        Instant now = Instant.now();
        Long draftId = insertDraftWithSource(
                "TIMER", now.minusSeconds(3600), now.minusSeconds(30 * 60), now.minusSeconds(30 * 60));

        assertThat(activeStudySessionService.findStaleDraftIds()).doesNotContain(draftId);
    }

    @Test
    void TIMER_draft는_2시간_넘게_보고가_없으면_마지막_보고_시각으로_확정된다() {
        Instant now = clock.instant();
        Instant lastReported = now.minusSeconds(2 * 3600 + 60);
        Long draftId = insertDraftWithSource("TIMER", now.minusSeconds(3 * 3600), lastReported, lastReported);

        assertThat(activeStudySessionService.findStaleDraftIds()).contains(draftId);
        activeStudySessionService.finalizeDraft(draftId);

        java.sql.Timestamp endedAt = jdbcTemplate.queryForObject(
                "SELECT max(ended_at) FROM study_session WHERE user_id = ?", java.sql.Timestamp.class, userId);
        assertThat(endedAt.toInstant()).isEqualTo(lastReported.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void TIMER_draft에_2시간이_되기_직전_새_보고가_오면_확정_대상이_아니다() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant started = now.minusSeconds(2 * 3600 + 600);
        Instant previousSeen = now.minusSeconds(2 * 3600 - 60);
        Long draftId = insertDraftWithSource("TIMER", started, started.plusSeconds(660), previousSeen);

        activeStudySessionService.reportSnapshot(
                userId,
                new ActiveSessionSnapshotRequest(
                        started, now, 7800, 7800, List.of(), null, null, StudySessionSource.TIMER),
                true);

        java.sql.Timestamp lastSeen = jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM active_study_session WHERE id = ?", java.sql.Timestamp.class, draftId);
        assertThat(lastSeen.toInstant()).isAfter(previousSeen);
        assertThat(activeStudySessionService.findStaleDraftIds()).doesNotContain(draftId);
    }

    @Test
    void MEASURED_draft는_보고가_끊긴_지_정확히_5분이면_확정_대상이_아니고_1초_더_지나면_대상이다() {
        Instant now = clock.instant();
        Instant atGrace = now.minusSeconds(5 * 60);
        Instant pastGrace = atGrace.minusSeconds(1);
        Long atGraceDraft = insertDraftWithSource("MEASURED", now.minusSeconds(1200), atGrace, atGrace);
        Long pastGraceDraft = insertDraftWithSource("MEASURED", now.minusSeconds(1300), pastGrace, pastGrace);

        assertThat(activeStudySessionService.findStaleDraftIds())
                .doesNotContain(atGraceDraft)
                .contains(pastGraceDraft);
    }

    @Test
    void TIMER_draft는_보고가_끊긴_지_정확히_2시간이면_확정_대상이_아니고_1초_더_지나면_대상이다() {
        Instant now = clock.instant();
        Instant atGrace = now.minusSeconds(2 * 3600);
        Instant pastGrace = atGrace.minusSeconds(1);
        Long atGraceDraft = insertDraftWithSource("TIMER", now.minusSeconds(3 * 3600), atGrace, atGrace);
        Long pastGraceDraft = insertDraftWithSource("TIMER", now.minusSeconds(3 * 3600 + 60), pastGrace, pastGrace);

        assertThat(activeStudySessionService.findStaleDraftIds())
                .doesNotContain(atGraceDraft)
                .contains(pastGraceDraft);
    }

    @Test
    void 같은_유저의_오래된_TIMER_draft만_확정되고_새로_시작한_draft는_남는다() {
        Instant now = clock.instant();
        Instant oldReported = now.minusSeconds(2 * 3600 + 60);
        insertDraftWithSource("TIMER", now.minusSeconds(3 * 3600), oldReported, oldReported);
        Long freshDraft =
                insertDraftWithSource("MEASURED", now.minusSeconds(600), now.minusSeconds(30), now.minusSeconds(30));

        new ActiveSessionFinalizeScheduler(activeStudySessionService).finalizeStaleDrafts();

        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM active_study_session WHERE user_id = ?", Long.class, userId))
                .containsExactly(freshDraft);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT source FROM study_session WHERE user_id = ?", String.class, userId))
                .isNotEmpty()
                .containsOnly("TIMER");
        java.sql.Timestamp endedAt = jdbcTemplate.queryForObject(
                "SELECT max(ended_at) FROM study_session WHERE user_id = ?", java.sql.Timestamp.class, userId);
        assertThat(endedAt.toInstant()).isEqualTo(oldReported);
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
    // finalizeStaleDrafts()를 직접 호출해 검증한다(백그라운드 틱에 기대지 않도록 여기서 인스턴스를 직접 생성한다).
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
