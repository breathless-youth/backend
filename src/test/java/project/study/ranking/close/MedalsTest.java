package project.study.ranking.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

class MedalsTest {

    private static final RankingBoard DAILY = new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.DAILY, null);
    private static final RankingBoard RATE = new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.WEEKLY, null);
    private static final Instant T = Instant.parse("2026-10-09T15:00:00Z");

    /** userId는 인자 순서(1부터), 정렬은 Standings가 한다. */
    private static Standings standings(double... values) {
        List<RankingEntry> entries = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            entries.add(RankingEntry.of(i + 1, "u" + (i + 1), values[i], T));
        }
        return Standings.of(entries, T);
    }

    @Test
    void 시간_판은_30분_미만이면_메달이_없고_다음_순위로_당기지_않는다() {
        assertThat(Medals.of(DAILY, standings(2400, 1799, 1900, 1000)))
                .extracting(RankedEntry::rank, medal -> medal.entry().userId())
                .containsExactly(tuple(1, 1L), tuple(2, 3L));
    }

    @Test
    void 일등이_30분_미만이면_아무도_받지_않는다() {
        assertThat(Medals.of(DAILY, standings(1700, 1000))).isEmpty();
    }

    @Test
    void 정확히_30분이면_받는다() {
        assertThat(Medals.of(DAILY, standings(1800)))
                .extracting(RankedEntry::rank)
                .containsExactly(1);
    }

    @Test
    void 집중률_판은_값과_상관없이_3위까지_받는다() {
        assertThat(Medals.of(RATE, standings(91.2, 88.0, 75.5, 60.0)))
                .extracting(RankedEntry::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 기록_값은_소수_1자리로_반올림한다() {
        assertThat(Medals.recordValue(90.909)).isEqualByComparingTo("90.9");
        assertThat(Medals.recordValue(90.95)).isEqualByComparingTo("91.0");
        assertThat(Medals.recordValue(3000)).isEqualByComparingTo("3000.0");
    }
}
