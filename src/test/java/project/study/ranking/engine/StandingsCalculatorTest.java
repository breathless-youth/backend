package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

class StandingsCalculatorTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private List<RankingEntry> compute(RankingBoard board, Long only) {
        List<RankingEntry> entries =
                calculator.compute(board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), only);
        return Standings.of(entries, NOW).entries();
    }

    @Test
    void 순공은_확정_세션에_진행_중_조각을_더한다() {
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 3000);
        draft(a, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        long b = user("b");
        session(b, kst(10, 7, 9, 0), 60, 3300);

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), null))
                .extracting(RankingEntry::nickname, RankingEntry::value, RankingEntry::focusing)
                .containsExactly(tuple("a", 3600.0, true), tuple("b", 3300.0, false));
    }

    @Test
    void 일분_미만_조각과_탈퇴자는_빠지고_진행_중만_있는_사람도_들어간다() {
        long tiny = user("tiny");
        session(tiny, kst(10, 6, 9, 0), 1, 59);
        long gone = user("gone");
        session(gone, kst(10, 6, 9, 0), 60, 3000);
        withdraw(gone);
        long goneLive = user("goneLive");
        draft(goneLive, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        withdraw(goneLive);
        long live = user("live");
        draft(live, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), null))
                .extracting(RankingEntry::nickname)
                .containsExactly("live");
    }

    @Test
    void 집중률은_참가_조건을_넘은_사람만_넣는다() {
        long in = user("in");
        session(in, kst(10, 6, 0, 30), 720, 39_600);
        long out = user("out");
        session(out, kst(10, 7, 0, 30), 540, 32_400);

        assertThat(compute(new RankingBoard(FOCUS_RATE, WEEKLY, null), null))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.nickname()).isEqualTo("in");
                    assertThat(e.value()).isCloseTo(91.67, within(0.01));
                    assertThat(e.focusSec()).isEqualTo(39_600);
                    assertThat(e.studySec()).isEqualTo(43_200);
                });
    }

    @Test
    void 시간대는_그_구간_순공만_더하고_지금_그_구간에서_집중_중인_사람만_집중_중이다() {
        long early = user("early");
        session(early, kst(10, 10, 6, 30), 60, 3600);
        long now = user("now");
        draft(now, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(compute(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.MORNING), null))
                .extracting(RankingEntry::nickname, RankingEntry::value)
                .containsExactly(tuple("early", 1800.0));
        assertThat(compute(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.AFTERNOON), null))
                .extracting(RankingEntry::nickname, RankingEntry::value, RankingEntry::focusing)
                .containsExactly(tuple("now", 600.0, true));
    }

    @Test
    void 한_사용자만_계산할_수_있다() {
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 3000);
        long b = user("b");
        session(b, kst(10, 7, 9, 0), 60, 3300);

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), a))
                .extracting(RankingEntry::userId)
                .containsExactly(a);
    }

    @Test
    void 명예의_전당_연속_일수는_최장_연속이다() {
        long k = user("k");
        for (int day : new int[] {1, 2, 3, 5, 6}) {
            session(k, kst(10, day, 9, 0), 15, 900);
        }

        assertThat(compute(new RankingBoard(MAX_STREAK, null, null), null))
                .extracting(RankingEntry::value)
                .containsExactly(3.0);
    }
}
