package project.study.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.metrics.report.fact.ExperimentFact;
import project.study.push.repository.PushExperimentQueries;

/** 첫 세션 실험의 대상 판정·배정·첫 세션 채우기·발송 자리 (BY-1070). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PushExperimentQueriesTest {

    private static final String EXPERIMENT = "first_session_5min";
    private static final Instant T0 = Instant.parse("2020-03-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);

    @Autowired
    private PushExperimentQueries queries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser(Instant createdAt) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, ?, now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                Timestamp.from(createdAt));
    }

    private void insertToken(long userId, boolean enabled) {
        jdbcTemplate.update(
                "insert into push_token (user_id, token, platform, notifications_enabled, created_at, updated_at) "
                        + "values (?, ?, 'IOS', ?, now(), now())",
                userId,
                UUID.randomUUID().toString(),
                enabled);
    }

    private long eligibleUser(Instant createdAt) {
        long userId = insertUser(createdAt);
        insertToken(userId, true);
        return userId;
    }

    private long insertSession(
            long userId, Instant startedAt, Instant submissionStartedAt, int focusSec, String source) {
        return jdbcTemplate.queryForObject(
                "insert into study_session "
                        + "(user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, java.time.ZoneId.of("Asia/Seoul"))),
                Timestamp.from(startedAt),
                submissionStartedAt == null ? null : Timestamp.from(submissionStartedAt),
                Timestamp.from(startedAt.plusSeconds(focusSec)),
                focusSec,
                focusSec,
                source);
    }

    private long insertSession(long userId, Instant startedAt, int focusSec) {
        return insertSession(userId, startedAt, startedAt, focusSec, "MEASURED");
    }

    @Test
    void 대상은_창_안에_가입하고_측정_세션도_진행_중_세션도_없고_알림이_켜진_활성_사용자다() {
        long eligible = eligibleUser(T0);
        long manualOnly = eligibleUser(T0.plusSeconds(1));
        insertSession(manualOnly, T0.plusSeconds(60), T0.plusSeconds(60), 600, "MANUAL");
        long measured = eligibleUser(T0.plusSeconds(2));
        insertSession(measured, T0.plusSeconds(60), 30);
        long active = eligibleUser(T0.plusSeconds(3));
        jdbcTemplate.update(
                "insert into active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, 0, 0)",
                active,
                Timestamp.from(T0),
                Timestamp.from(T0),
                Timestamp.from(T0));
        long noToken = insertUser(T0.plusSeconds(4));
        long disabled = insertUser(T0.plusSeconds(5));
        insertToken(disabled, false);
        long deleted = eligibleUser(T0.plusSeconds(6));
        jdbcTemplate.update("update users set status = 'DELETE' where id = ?", deleted);
        long outside = eligibleUser(T1);

        assertThat(queries.findFirstSessionCandidates(T0, T1))
                .containsExactly(eligible, manualOnly)
                .doesNotContain(measured, active, noToken, disabled, deleted, outside);
    }

    @Test
    void 배정은_처음_한_번만_되고_그_뒤에는_처음_집단을_돌려준다() {
        long userId = eligibleUser(T0);

        assertThat(queries.assign(EXPERIMENT, userId, "PUSH", T0)).isEqualTo("PUSH");
        assertThat(queries.assign(EXPERIMENT, userId, "CONTROL", T1)).isEqualTo("PUSH");
    }

    @Test
    void 첫_세션은_가장_이른_측정_묶음이고_자정_분할_조각의_순공을_합친다() {
        long userId = eligibleUser(T0);
        queries.assign(EXPERIMENT, userId, "CONTROL", T0);
        Instant first = T0.plusSeconds(7200);
        insertSession(userId, T0.plusSeconds(3600), T0.plusSeconds(3600), 900, "MANUAL");
        long firstPiece = insertSession(userId, first, first, 120, "MEASURED");
        insertSession(userId, first.plusSeconds(600), first, 200, "MEASURED");
        insertSession(userId, first.plusSeconds(86_400), 1_000);

        assertThat(queries.fillFirstSessions(EXPERIMENT)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap(
                        "select first_session_id, first_session_at, first_focus_sec from push_experiment_assignment "
                                + "where experiment = ? and user_id = ?",
                        EXPERIMENT,
                        userId))
                .containsEntry("first_session_id", firstPiece)
                .containsEntry("first_session_at", Timestamp.from(first))
                .containsEntry("first_focus_sec", 320);
    }

    @Test
    void 한_번_채운_첫_세션은_바꾸지_않는다() {
        long userId = eligibleUser(T0);
        queries.assign(EXPERIMENT, userId, "PUSH", T0);
        long session = insertSession(userId, T1, 100);
        queries.fillFirstSessions(EXPERIMENT);
        jdbcTemplate.update("update study_session set focus_sec = 999 where id = ?", session);

        assertThat(queries.fillFirstSessions(EXPERIMENT)).isZero();
        assertThat(queries.findFacts(EXPERIMENT))
                .singleElement()
                .isEqualTo(new ExperimentFact(userId, "PUSH", T0, T1, 100, null, true, true));
    }

    @Test
    void 첫_세션이_없으면_비워_둔다() {
        long userId = insertUser(T0);
        queries.assign(EXPERIMENT, userId, "CONTROL", T0);

        assertThat(queries.fillFirstSessions(EXPERIMENT)).isZero();
        assertThat(queries.findFacts(EXPERIMENT))
                .singleElement()
                .isEqualTo(new ExperimentFact(userId, "CONTROL", T0, null, null, null, false, false));
    }

    private void insertWindow(LocalDate date) {
        jdbcTemplate.update(
                "insert into amplitude_export_window (window_date, event_count, skipped) values (?, 0, 0)",
                java.sql.Date.valueOf(date));
    }

    private void insertEvent(long userId, LocalDate date, String key) {
        jdbcTemplate.update(
                "insert into amplitude_daily_event (user_id, event_date, event_key, count) values (?, ?, ?, 1)",
                userId,
                java.sql.Date.valueOf(date),
                key);
    }

    @Test
    void D7은_가입_7일_뒤_그날과_다음_날_적재가_끝나면_그날_세션_시작으로_한_번_채운다() {
        // T0 = 2020-03-01 09:00 KST → 가입일 3/1, D7 = 3/8
        LocalDate day7 = LocalDate.of(2020, 3, 8);
        long returned = insertUser(T0);
        long otherDay = insertUser(T0);
        long otherEvent = insertUser(T0);
        for (long userId : new long[] {returned, otherDay, otherEvent}) {
            queries.assign(EXPERIMENT, userId, "PUSH", T0);
        }
        insertEvent(returned, day7, "study_session_started");
        insertEvent(otherDay, day7.minusDays(1), "study_session_started");
        insertEvent(otherEvent, day7, "app_launched");
        insertWindow(day7);

        assertThat(queries.fillDaySeven(EXPERIMENT)).as("다음 날 적재 전에는 채우지 않는다").isZero();

        insertWindow(day7.plusDays(1));
        assertThat(queries.fillDaySeven(EXPERIMENT)).isEqualTo(3);
        assertThat(queries.findFacts(EXPERIMENT))
                .extracting(ExperimentFact::userId, ExperimentFact::daySevenReturned)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(returned, true),
                        org.assertj.core.groups.Tuple.tuple(otherDay, false),
                        org.assertj.core.groups.Tuple.tuple(otherEvent, false));

        insertEvent(otherDay, day7, "study_session_started");
        assertThat(queries.fillDaySeven(EXPERIMENT)).as("한 번 채운 값은 바꾸지 않는다").isZero();
    }

    @Test
    void 발송_자리는_사람당_하루_하나다() {
        long userId = eligibleUser(T0);
        LocalDate day = LocalDate.of(2020, 3, 1);
        UUID first = UUID.randomUUID();

        assertThat(queries.reserveSend(first, userId, "first_session_d0", day, T0))
                .isTrue();
        assertThat(queries.reserveSend(UUID.randomUUID(), userId, "first_session_d1", day, T1))
                .isFalse();
        assertThat(queries.reserveSend(UUID.randomUUID(), userId, "first_session_d1", day.plusDays(1), T1))
                .isTrue();

        queries.recordAccepted(first, 2);
        assertThat(jdbcTemplate.queryForObject(
                        "select fcm_accepted from push_send where push_id = ?", Integer.class, first))
                .isEqualTo(2);
    }
}
