package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingCalendar.Window;
import project.study.studysession.entity.TimeSlot;

class RankingCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-10은 토요일 — 그 주는 10/5(월)~10/11(일) */
    private static final Instant SAT_15 = at(10, 10, 15);

    private static Instant at(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, KST).toInstant();
    }

    private static LocalDate date(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void 일간은_그날이고_다음날_0시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, DAILY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 10), date(10, 10), at(10, 11, 0)));
    }

    @Test
    void 주간은_월요일부터_일요일이고_다음_월요일_0시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, WEEKLY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 5), date(10, 11), at(10, 12, 0)));
    }

    @Test
    void 월간은_1일부터_말일이다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, MONTHLY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 1), date(10, 31), at(11, 1, 0)));
    }

    @Test
    void 직전_기간은_한_기간_앞이다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, DAILY, null), SAT_15, -1))
                .isEqualTo(new Window(date(10, 9), date(10, 9), at(10, 10, 0)));
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, MONTHLY, null), SAT_15, -1))
                .isEqualTo(new Window(date(9, 1), date(9, 30), at(10, 1, 0)));
    }

    @Test
    void 심야_일간은_자정_뒤에도_전날_판이고_4시에_마감한다() {
        RankingBoard night = new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT);

        assertThat(RankingCalendar.window(night, at(10, 11, 2), 0))
                .isEqualTo(new Window(date(10, 10), date(10, 10), at(10, 11, 4)));
        assertThat(RankingCalendar.window(night, at(10, 11, 5), 0))
                .isEqualTo(new Window(date(10, 11), date(10, 11), at(10, 12, 4)));
    }

    @Test
    void 심야_주간은_월요일_4시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.NIGHT), at(10, 12, 2), 0))
                .isEqualTo(new Window(date(10, 5), date(10, 11), at(10, 12, 4)));
    }

    @Test
    void 명예의_전당은_처음부터_오늘까지이고_마감이_없다() {
        assertThat(RankingCalendar.window(new RankingBoard(TOTAL_TIME, null, null), SAT_15, 0))
                .isEqualTo(new Window(RankingCalendar.ALL_TIME_START, date(10, 10), null));
    }
}
