package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StudySessionRankingQueriesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate FROM = LocalDate.of(2031, 3, 2);
    private static final LocalDate TO = LocalDate.of(2031, 3, 8);
    private static final LocalDate TODAY = LocalDate.of(2031, 3, 31);

    @Autowired
    private RankingSource source;

    @Autowired
    private JdbcTemplate jdbc;

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "q-" + UUID.randomUUID());
    }

    private String nickname(long userId) {
        return jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, userId);
    }

    private static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(KST).toInstant();
    }

    /** 세션 행을 직접 넣는다 — 2031년은 미래라 제출 API 검증을 못 지난다. 반환은 세션 id */
    private long session(long userId, LocalDate date, int hour, int minutes, int focusSec) {
        Instant start = at(date, hour);
        return jdbc.queryForObject(
                """
                INSERT INTO study_session
                    (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, auto_finalized)
                VALUES (?, ?, ?, ?, ?, ?, ?, false) RETURNING id""",
                Long.class,
                userId,
                date,
                start.atOffset(ZoneOffset.UTC),
                start.atOffset(ZoneOffset.UTC),
                start.plusSeconds(minutes * 60L).atOffset(ZoneOffset.UTC),
                minutes * 60,
                focusSec);
    }

    private void slot(long sessionId, TimeSlot slot, LocalDate date, int focusSec) {
        jdbc.update(
                "INSERT INTO study_session_slot (session_id, slot, slot_date, focus_sec) VALUES (?, ?, ?, ?)",
                sessionId,
                slot.name(),
                date,
                focusSec);
    }

    @Test
    void 기간_합계는_일분_미만_조각과_기간_밖과_탈퇴자를_빼고_사용자별로_낸다() {
        long a = user();
        long b = user();
        long gone = user();
        session(a, FROM.plusDays(1), 9, 60, 3000);
        session(a, FROM.plusDays(2), 9, 1, 59);
        session(a, LocalDate.of(2031, 3, 20), 9, 30, 1000);
        session(b, FROM.plusDays(2), 10, 30, 1800);
        session(gone, FROM.plusDays(1), 9, 60, 3000);
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", gone);

        List<RankingTotalRow> rows = source.periodTotals(FROM, TO, null);

        assertThat(rows)
                .filteredOn(r -> Set.of(a, b, gone).contains(r.userId()))
                .extracting(
                        RankingTotalRow::userId,
                        RankingTotalRow::focusSec,
                        RankingTotalRow::studySec,
                        RankingTotalRow::achievedAt)
                .containsExactlyInAnyOrder(
                        tuple(a, 3000L, 3600L, at(FROM.plusDays(1), 10)),
                        tuple(b, 1800L, 1800L, at(FROM.plusDays(2), 10).plusSeconds(1800)));
        assertThat(source.periodTotals(FROM, TO, a))
                .extracting(RankingTotalRow::nickname)
                .containsExactly(nickname(a));
    }

    @Test
    void 시간대_합계는_그_구간_행만_더한다() {
        long a = user();
        long sessionId = session(a, FROM.plusDays(1), 6, 60, 3600);
        slot(sessionId, TimeSlot.DAWN, FROM.plusDays(1), 2400);
        slot(sessionId, TimeSlot.MORNING, FROM.plusDays(1), 1200);

        assertThat(source.slotTotals(TimeSlot.MORNING, FROM, TO, a))
                .extracting(RankingTotalRow::focusSec)
                .containsExactly(1200L);
    }

    @Test
    void 누적_일수는_오늘까지_일분_이상인_날을_세고_마지막_날에_처음_넘긴_시각이_도달_시각이다() {
        long a = user();
        session(a, LocalDate.of(2031, 3, 3), 9, 30, 1000);
        session(a, LocalDate.of(2031, 3, 4), 9, 1, 59);
        session(a, LocalDate.of(2031, 3, 20), 9, 30, 1000);
        session(a, LocalDate.of(2031, 3, 20), 14, 30, 1000);
        session(a, LocalDate.of(2031, 4, 2), 9, 30, 1000);

        assertThat(source.studyDays(TODAY, a))
                .extracting(RankingDaysRow::days, RankingDaysRow::achievedAt)
                .containsExactly(tuple(2, at(LocalDate.of(2031, 3, 20), 9).plusSeconds(1800)));
    }

    @Test
    void 최장_연속은_가장_먼저_찍은_최장_구간이고_십분_미만_날은_끊는다() {
        long c = user();
        for (int day : new int[] {1, 2, 3, 5, 6, 7}) {
            session(c, LocalDate.of(2031, 3, day), 9, 15, 900);
        }
        session(c, LocalDate.of(2031, 3, 4), 9, 15, 500);

        assertThat(source.maxStreaks(TODAY, c))
                .extracting(
                        RankingStreakRow::days,
                        RankingStreakRow::startDate,
                        RankingStreakRow::endDate,
                        RankingStreakRow::achievedAt)
                .containsExactly(tuple(
                        3,
                        LocalDate.of(2031, 3, 1),
                        LocalDate.of(2031, 3, 3),
                        at(LocalDate.of(2031, 3, 3), 9).plusSeconds(900)));
    }

    @Test
    void 닉네임은_탈퇴하지_않은_사용자만_읽는다() {
        long a = user();
        long gone = user();
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", gone);

        assertThat(source.activeNicknames(List.of(a, gone))).containsOnlyKeys(a);
    }

    @Test
    void 닉네임이_없는_사용자는_닉네임_맵에서_빠진다() {
        long a = user();
        long noNickname = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('test', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());

        assertThat(source.activeNicknames(List.of(a, noNickname))).containsOnlyKeys(a);
    }
}
