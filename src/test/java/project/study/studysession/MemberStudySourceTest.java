package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import io.sentry.Sentry;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.MemberStudy;
import project.study.studysession.dto.MemberStudy.Presence;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.ActiveStudySessionService;
import project.study.studysession.service.MemberStudySource;

/**
 * 그룹 멤버 책상이 읽는 앱 전체 공부 상태·순공 (BY-1124). 기준은 랭킹과 같다 — 측정 세션만, 1분 미만 조각 제외, 진행 중 draft 포함. 상태는 마지막
 * 수신이 가장 최근인 draft 하나로 60초 창 안에서 판단한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberStudySourceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-10-14(수) 15:00 KST — 이번 주는 10/12(월)부터다. */
    private static final Instant AS_OF = kst(14, 15, 0, 0);

    @Autowired
    private MemberStudySource source;

    @Autowired
    private ActiveStudySessionRepository drafts;

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> createdUserIds = new ArrayList<>();

    // 랭킹의 livePieces·확정 스케줄러 테스트는 전 유저 draft를 읽는다 — 여기서 만든 draft가 남으면 그 단언이 실행 순서에 따라 흔들린다
    @AfterEach
    void cleanUpDrafts() {
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM active_study_session WHERE user_id = ?", id));
        createdUserIds.clear();
    }

    private long user() {
        long id = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "desk-" + UUID.randomUUID().toString().substring(0, 8));
        createdUserIds.add(id);
        return id;
    }

    private static Instant kst(int day, int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, KST).toInstant();
    }

    /** 확정 세션 한 조각 — 날짜는 시작 시각의 KST 날짜, 끝은 시작 + 순공. 제출 시작은 시작과 같다. */
    private void session(long userId, Instant start, int focusSec, String sessionSource) {
        jdbc.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId,
                Date.valueOf(LocalDate.ofInstant(start, KST)),
                Timestamp.from(start),
                Timestamp.from(start),
                Timestamp.from(start.plusSeconds(focusSec)),
                focusSec,
                focusSec,
                sessionSource);
    }

    /** 진행 중 스냅샷 — 마지막 수신 = reportedAt, 총공부는 길이 전체. events는 JSON 배열 문자열 */
    private void draft(long userId, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        drafts.upsertSnapshot(userId, start, reportedAt, reportedAt, length, focusSec, events);
    }

    /** reportedAt에서 닫힌(= 지금 진행 중인) 이벤트 하나 — 앱은 진행 중 이벤트를 reportedAt에서 닫아 보낸다. */
    private static String openEvent(String status, Instant reportedAt) {
        return "[{\"status\":\"%s\",\"startedAt\":\"%s\",\"endedAt\":\"%s\"}]"
                .formatted(status, reportedAt.minusSeconds(60), reportedAt);
    }

    private MemberStudy of(long userId) {
        return source.of(List.of(userId), AS_OF).get(userId);
    }

    @Test
    void 확정_측정_세션은_오늘과_이번_주에_더하고_1분_미만과_수동_기록은_뺀다() {
        long me = user();
        session(me, kst(14, 10, 0, 0), 1800, "MEASURED"); // 오늘 30분, 10:30에 끝남
        session(me, kst(12, 0, 30, 0), 1200, "MEASURED"); // 이번 주 월요일
        session(me, kst(11, 20, 0, 0), 3600, "MEASURED"); // 지난주 일요일 — 이번 주가 아니다
        session(me, kst(14, 11, 0, 0), 59, "MEASURED"); // 1분 미만 — 기록 탭에 안 보인다
        session(me, kst(14, 12, 0, 0), 3600, "MANUAL"); // 수동 기록 — 랭킹처럼 뺀다

        MemberStudy study = of(me);

        assertThat(study.todayFocusSec()).isEqualTo(1800);
        assertThat(study.weekFocusSec()).isEqualTo(3000);
        assertThat(study.presence()).isEqualTo(Presence.IDLE);
        assertThat(study.accumulating()).isFalse();
        // 59초 세션·수동 기록이 더 늦게 끝났어도 마지막 공부는 1분 이상 측정 세션의 끝이다
        assertThat(study.lastStudiedAt()).isEqualTo(kst(14, 10, 30, 0));
    }

    @Test
    void 집중_중인_draft는_STUDYING이고_asOf까지_늘린_몫이_순공에_들어간다() {
        long me = user();
        session(me, kst(14, 10, 0, 0), 1800, "MEASURED");
        draft(me, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isTrue();
        assertThat(study.todayFocusSec()).isEqualTo(1800 + 600);
        assertThat(study.weekFocusSec()).isEqualTo(1800 + 600);
        assertThat(study.lastStudiedAt()).isEqualTo(AS_OF.minusSeconds(10));
    }

    @Test
    void 열린_이벤트가_PAUSE면_PAUSED이고_쌓이지_않는다() {
        long me = user();
        Instant reported = AS_OF.minusSeconds(10);
        draft(me, AS_OF.minusSeconds(600), reported, 500, openEvent("PAUSE", reported));

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.PAUSED);
        assertThat(study.accumulating()).isFalse();
        assertThat(study.todayFocusSec()).isEqualTo(500); // 집중 중이 아니라 늘리지 않는다
    }

    @Test
    void 열린_이벤트가_PAUSE가_아니면_STUDYING이지만_쌓이지_않는다() {
        long me = user();
        Instant reported = AS_OF.minusSeconds(10);
        draft(me, AS_OF.minusSeconds(600), reported, 500, openEvent("PHONE", reported));

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isFalse();
        assertThat(study.todayFocusSec()).isEqualTo(500);
    }

    @Test
    void 지난_이벤트만_있으면_집중_중이다() {
        long me = user();
        Instant reported = AS_OF.minusSeconds(10);
        String closedPause = "[{\"status\":\"PAUSE\",\"startedAt\":\"%s\",\"endedAt\":\"%s\"}]"
                .formatted(reported.minusSeconds(300), reported.minusSeconds(200));
        draft(me, AS_OF.minusSeconds(600), reported, 400, closedPause);

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isTrue();
    }

    @Test
    void 마지막_수신이_61초_전이면_IDLE이지만_그_draft의_순공은_센다() {
        long me = user();
        draft(me, AS_OF.minusSeconds(600), AS_OF.minusSeconds(61), 539, "[]");

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.IDLE);
        assertThat(study.accumulating()).isFalse();
        // 하트비트가 끊긴 draft도 자동 확정되면 같은 값이 된다 — 랭킹처럼 늘리지 않고 그대로 센다
        assertThat(study.todayFocusSec()).isEqualTo(539);
        assertThat(study.lastStudiedAt()).isEqualTo(AS_OF.minusSeconds(61));
    }

    @Test
    void 마지막_수신이_정확히_60초_전이면_아직_STUDYING이다() {
        long me = user();
        draft(me, AS_OF.minusSeconds(600), AS_OF.minusSeconds(60), 540, "[]");

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isTrue();
        assertThat(study.todayFocusSec()).isEqualTo(600);
    }

    @Test
    void 제출이_이미_확정된_draft는_순공에_두_번_들어가지_않고_상태에도_안_쓴다() {
        long me = user();
        Instant start = AS_OF.minusSeconds(1210);
        session(me, start, 1200, "MEASURED"); // 제출 시작 = draft 시작
        // 제출 직전 하트비트가 버퍼에서 뒤늦게 flush돼 되살아난 draft
        draft(me, start, AS_OF.minusSeconds(10), 1190, "[]");

        MemberStudy study = of(me);

        assertThat(study.todayFocusSec()).isEqualTo(1200);
        assertThat(study.weekFocusSec()).isEqualTo(1200);
        // 이미 끝난 세션이라 공부 중으로 보이지 않는다
        assertThat(study.presence()).isEqualTo(Presence.IDLE);
        assertThat(study.accumulating()).isFalse();
        assertThat(study.lastStudiedAt()).isEqualTo(start.plusSeconds(1200));
    }

    @Test
    void draft_조각이_1분_미만이면_순공에_안_더하지만_상태는_STUDYING이다() {
        long me = user();
        draft(me, AS_OF.minusSeconds(40), AS_OF.minusSeconds(10), 30, "[]"); // 늘려도 40초

        MemberStudy study = of(me);

        assertThat(study.todayFocusSec()).isZero();
        assertThat(study.weekFocusSec()).isZero();
        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isTrue();
        assertThat(study.lastStudiedAt()).isEqualTo(AS_OF.minusSeconds(10));
    }

    @Test
    void 월요일_자정을_걸친_draft는_일요일_몫을_이번_주에_넣지_않는다() {
        long me = user();
        Instant mondayAsOf = kst(12, 0, 30, 0);
        draft(me, kst(11, 23, 30, 0), mondayAsOf.minusSeconds(10), 3590, "[]"); // 늘리면 3600초, 자정에서 반씩

        MemberStudy study = source.of(List.of(me), mondayAsOf).get(me);

        assertThat(study.todayFocusSec()).isEqualTo(1800);
        assertThat(study.weekFocusSec()).isEqualTo(1800);
    }

    @Test
    void 마지막_수신이_가장_최근인_draft로_상태를_정한다() {
        long me = user();
        draft(me, AS_OF.minusSeconds(3600), AS_OF.minusSeconds(20), 3580, "[]"); // 옛 draft — 집중 중
        Instant reported = AS_OF.minusSeconds(5);
        draft(me, AS_OF.minusSeconds(300), reported, 200, openEvent("PAUSE", reported)); // 새 draft — 일시정지

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.PAUSED);
        assertThat(study.accumulating()).isFalse();
        // 순공은 두 draft 몫을 모두 센다 (랭킹과 같다)
        assertThat(study.todayFocusSec()).isEqualTo(3600 + 200);
        assertThat(study.lastStudiedAt()).isEqualTo(reported);
    }

    @Test
    void 여러_사람을_한_번에_읽어도_사람마다_따로_세고_기록이_없으면_IDLE_0_0_null이다() {
        long studied = user();
        long studying = user();
        long nothing = user();
        session(studied, kst(14, 10, 0, 0), 1800, "MEASURED");
        draft(studying, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");

        Map<Long, MemberStudy> studies = source.of(List.of(studied, studying, nothing), AS_OF);

        assertThat(studies).containsOnlyKeys(studied, studying, nothing);
        assertThat(studies.get(studied).todayFocusSec()).isEqualTo(1800);
        assertThat(studies.get(studied).presence()).isEqualTo(Presence.IDLE);
        assertThat(studies.get(studying).todayFocusSec()).isEqualTo(600);
        assertThat(studies.get(studying).presence()).isEqualTo(Presence.STUDYING);
        assertThat(studies.get(nothing)).isEqualTo(new MemberStudy(nothing, Presence.IDLE, false, 0, 0, null));
    }

    /**
     * 마지막 공부는 사람마다 가장 늦은 stat_date의 가장 늦은 끝 시각이다(MAX(끝)과 같은 값). 날짜가 여럿이고, 자정에서 쪼개진 세션이 있고, 끝 시각이
     * 빈 옛 행은 시작 시각으로 본다.
     */
    @Test
    void 마지막_공부는_사람마다_가장_늦은_날짜의_가장_늦은_끝이다() {
        long split = user();
        long legacy = user();
        long older = user();
        long onlyShort = user();
        long nothing = user();
        // 자정에서 쪼개진 세션 — 10/13 23:30~24:00, 10/14 00:00~00:20, 그 앞에 더 이른 날
        session(split, kst(10, 9, 0, 0), 3600, "MEASURED");
        session(split, kst(13, 23, 30, 0), 1800, "MEASURED");
        session(split, kst(14, 0, 0, 0), 1200, "MEASURED");
        // 끝 시각이 빈 옛 행은 시작 시각이 끝이다. 같은 날 더 이른 시작의 일반 행이 있어도 늦은 쪽이 이긴다
        jdbc.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, source) VALUES (?, ?, ?, ?, NULL, 600, 600, 'MEASURED')",
                legacy,
                Date.valueOf(LocalDate.of(2026, 10, 13)),
                Timestamp.from(kst(13, 18, 0, 0)),
                Timestamp.from(kst(13, 18, 0, 0)));
        session(legacy, kst(13, 8, 0, 0), 600, "MEASURED");
        // 같은 날 두 세션 — 먼저 넣은 쪽이 더 늦게 끝나도 늦은 끝 시각을 고른다
        session(older, kst(9, 20, 0, 0), 1200, "MEASURED");
        session(older, kst(9, 8, 0, 0), 600, "MEASURED");
        // 1분 미만 측정과 수동 기록뿐이면 없다
        session(onlyShort, kst(14, 8, 0, 0), 59, "MEASURED");
        session(onlyShort, kst(14, 9, 0, 0), 3600, "MANUAL");

        Map<Long, MemberStudy> studies = source.of(List.of(split, legacy, older, onlyShort, nothing), AS_OF);

        assertThat(studies.get(split).lastStudiedAt()).isEqualTo(kst(14, 0, 20, 0));
        assertThat(studies.get(legacy).lastStudiedAt()).isEqualTo(kst(13, 18, 0, 0));
        assertThat(studies.get(older).lastStudiedAt())
                .isEqualTo(kst(9, 20, 0, 0).plusSeconds(1200));
        assertThat(studies.get(onlyShort).lastStudiedAt()).isNull();
        assertThat(studies.get(nothing).lastStudiedAt()).isNull();
    }

    @Test
    void 읽지_못하는_draft는_책상_경로에서_건너뛰고_Sentry로_보내지_않는다() {
        long broken = user();
        long fine = user();
        draft(broken, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "{}"); // 배열이 아니라 events를 읽지 못한다
        draft(fine, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");

        try (MockedStatic<Sentry> sentry = Mockito.mockStatic(Sentry.class)) {
            Map<Long, MemberStudy> studies = source.of(List.of(broken, fine), AS_OF);

            assertThat(studies.get(broken).presence()).isEqualTo(Presence.IDLE);
            assertThat(studies.get(fine).presence()).isEqualTo(Presence.STUDYING);
            sentry.verify(() -> Sentry.captureException(any(Throwable.class)), never());
        }
    }

    @Test
    void 읽지_못하는_draft라도_랭킹_집계_경로는_그대로_Sentry로_알린다() {
        long broken = user();
        draft(broken, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "{}");

        try (MockedStatic<Sentry> sentry = Mockito.mockStatic(Sentry.class)) {
            activeStudySessionService.livePieces(AS_OF);

            sentry.verify(() -> Sentry.captureException(any(Throwable.class)), atLeastOnce());
        }
    }

    @Test
    void 아무도_없으면_빈_맵이다() {
        assertThat(source.of(List.of(), AS_OF)).isEmpty();
        assertThat(source.weekFocus(List.of(), AS_OF)).isEmpty();
    }

    @Test
    void weekFocus는_of의_이번_주_순공과_같고_모든_사람에_값이_있다() {
        long me = user();
        long nothing = user();
        session(me, kst(12, 0, 30, 0), 1200, "MEASURED");
        session(me, kst(11, 20, 0, 0), 3600, "MEASURED");
        draft(me, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");

        Map<Long, Long> week = source.weekFocus(List.of(me, nothing), AS_OF);

        assertThat(week).containsExactlyInAnyOrderEntriesOf(Map.of(me, 1200L + 600L, nothing, 0L));
        assertThat(week.get(me)).isEqualTo(of(me).weekFocusSec());
    }

    @Test
    void 바깥_트랜잭션이_REPEATABLE_READ가_아니면_거절한다() {
        // 확정 합계와 draft를 한 스냅샷에서 읽어야 그 사이 확정된 세션이 두 번 세지지 않는다
        long me = user();
        TransactionTemplate readCommitted = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> readCommitted.executeWithoutResult(status -> source.of(List.of(me), AS_OF)))
                .isInstanceOf(IllegalStateException.class);
    }
}
