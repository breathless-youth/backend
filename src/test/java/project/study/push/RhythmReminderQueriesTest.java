package project.study.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.push.repository.RhythmReminderQueries;
import project.study.push.repository.RhythmReminderQueries.Candidate;
import project.study.push.repository.RhythmReminderQueries.RecentStart;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RhythmReminderQueriesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2020, 3, 20);
    private static final String TYPE = "rhythm_reminder_usual";

    @Autowired
    private RhythmReminderQueries queries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private void insertToken(long userId, boolean enabled) {
        jdbcTemplate.update(
                "insert into push_token (user_id, token, platform, notifications_enabled, created_at, updated_at) "
                        + "values (?, ?, 'IOS', ?, now(), now())",
                userId,
                UUID.randomUUID().toString(),
                enabled);
    }

    private long eligibleUser() {
        long userId = insertUser();
        insertToken(userId, true);
        return userId;
    }

    private static Instant seoul(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute)).atZone(SEOUL).toInstant();
    }

    private void insertSession(
            long userId, Instant startedAt, Instant submissionStartedAt, int focusSec, String source, String zone) {
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, submission_started_at, ended_at, "
                        + "study_sec, focus_sec, source, time_zone) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, ZoneId.of(zone))),
                Timestamp.from(startedAt),
                Timestamp.from(submissionStartedAt),
                Timestamp.from(startedAt.plusSeconds(focusSec)),
                focusSec,
                focusSec,
                source,
                zone);
    }

    private void insertSession(long userId, Instant startedAt, int focusSec) {
        insertSession(userId, startedAt, startedAt, focusSec, "MEASURED", "Asia/Seoul");
    }

    private void insertSend(long userId, String type, LocalDate date) {
        jdbcTemplate.update(
                "insert into push_send (push_id, user_id, type, send_date, sent_at) values (?, ?, ?, ?, now())",
                UUID.randomUUID(),
                userId,
                type,
                java.sql.Date.valueOf(date));
    }

    private List<RecentStart> recentOf(long userId, Instant from) {
        return queries.findRecentStarts(from, 60).stream()
                .filter(start -> start.userId() == userId)
                .toList();
    }

    @Test
    void 대상은_알림이_켜진_토큰이_있고_1분_이상_측정한_날이_있는_활성_사용자이고_공부일과_시간대를_싣는다() {
        long eligible = eligibleUser();
        jdbcTemplate.update("update users set time_zone = 'America/Los_Angeles' where id = ?", eligible);
        insertSession(eligible, seoul(TODAY.minusDays(2), 19, 0), 600);
        insertSession(eligible, seoul(TODAY.minusDays(2), 21, 0), 600);
        insertSession(eligible, seoul(TODAY.minusDays(1), 19, 0), 600);
        long shortOnly = eligibleUser();
        insertSession(shortOnly, seoul(TODAY.minusDays(1), 19, 0), 59);
        long manualOnly = eligibleUser();
        Instant manualAt = seoul(TODAY.minusDays(1), 19, 0);
        insertSession(manualOnly, manualAt, manualAt, 600, "MANUAL", "Asia/Seoul");
        long disabled = insertUser();
        insertToken(disabled, false);
        insertSession(disabled, seoul(TODAY.minusDays(1), 19, 0), 600);
        long deleted = eligibleUser();
        insertSession(deleted, seoul(TODAY.minusDays(1), 19, 0), 600);
        jdbcTemplate.update("update users set status = 'DELETE' where id = ?", deleted);

        List<Candidate> candidates = queries.findCandidates(60);

        assertThat(candidates).contains(new Candidate(eligible, 2, ZoneId.of("America/Los_Angeles")));
        assertThat(candidates).extracting(Candidate::userId).doesNotContain(shortOnly, manualOnly, disabled, deleted);
    }

    @Test
    void 최근_세션은_from_이후만_읽고_자정_분할_조각은_하나로_묶고_세션_시간대를_싣는다() {
        long userId = eligibleUser();
        insertSession(userId, seoul(TODAY.minusDays(20), 9, 0), 600);
        Instant start = seoul(TODAY.minusDays(2), 23, 0);
        insertSession(userId, start, start, 3000, "MEASURED", "Asia/Seoul");
        insertSession(userId, seoul(TODAY.minusDays(1), 0, 0), start, 3000, "MEASURED", "Asia/Seoul");
        Instant abroad = seoul(TODAY.minusDays(1), 12, 0);
        insertSession(userId, abroad, abroad, 600, "MEASURED", "Europe/London");
        insertSession(userId, seoul(TODAY.minusDays(1), 15, 0), 30);

        assertThat(recentOf(userId, seoul(TODAY.minusDays(14), 0, 0)))
                .containsExactlyInAnyOrder(
                        new RecentStart(userId, start, SEOUL),
                        new RecentStart(userId, abroad, ZoneId.of("Europe/London")));
    }

    @Test
    void 오늘_기록이_있거나_공부_중이면_보낼_수_없다() {
        long free = eligibleUser();
        insertSession(free, seoul(TODAY.minusDays(1), 19, 0), 600);
        long studiedToday = eligibleUser();
        Instant todayAt = seoul(TODAY, 9, 0);
        insertSession(studiedToday, todayAt, todayAt, 30, "MANUAL", "Asia/Seoul");
        long studying = eligibleUser();
        jdbcTemplate.update(
                "insert into active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec) "
                        + "values (?, now(), now(), now(), 0, 0)",
                studying);

        assertThat(queries.canSend(free, TODAY)).isTrue();
        assertThat(queries.canSend(studiedToday, TODAY)).isFalse();
        assertThat(queries.canSend(studying, TODAY)).isFalse();
    }

    @Test
    void 마지막_발송일은_같은_type만_보고_없으면_비어_있다() {
        long userId = eligibleUser();
        assertThat(queries.lastSendDate(userId, TYPE)).isEmpty();

        insertSend(userId, TYPE, TODAY.minusDays(5));
        insertSend(userId, TYPE, TODAY.minusDays(3));
        insertSend(userId, "first_session_d0", TODAY.minusDays(1));

        assertThat(queries.lastSendDate(userId, TYPE)).contains(TODAY.minusDays(3));
    }
}
