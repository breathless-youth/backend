package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RankingEntryTest {

    private static final Instant T0 = Instant.parse("2026-10-10T14:59:50Z");

    private static RankingEntry focusing(double value) {
        return new RankingEntry(1, "u1", null, value, 0, T0, true, 600, 700);
    }

    @Test
    void 마감된_줄은_집중_중이어도_마감_시각까지만_올라가고_집중_중_표시가_꺼진다() {
        RankingEntry settled = focusing(100).settledAt(T0, T0.plusSeconds(4));

        assertThat(settled.value()).isEqualTo(104);
        assertThat(settled.focusSec()).isEqualTo(604);
        assertThat(settled.studySec()).isEqualTo(704);
        assertThat(settled.achievedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(settled.focusing()).isFalse();
    }

    @Test
    void 기준_시각이_이미_마감_뒤면_값은_그대로고_집중_중_표시만_꺼진다() {
        RankingEntry settled = focusing(100).settledAt(T0.plusSeconds(10), T0.plusSeconds(4));

        assertThat(settled).isEqualTo(new RankingEntry(1, "u1", null, 100, 0, T0, false, 600, 700));
    }

    @Test
    void 집중_중이_아닌_줄은_그대로다() {
        RankingEntry idle = RankingEntry.of(1, "u1", 100, T0);

        assertThat(idle.settledAt(T0, T0.plusSeconds(4))).isSameAs(idle);
    }

    @Test
    void 같은_값이면_보조_값이_큰_쪽이_먼저_도달했어도_앞이다() {
        RankingEntry early = RankingEntry.of(1, "a", null, 3, 100, T0);
        RankingEntry more = RankingEntry.of(2, "b", null, 3, 200, T0.plusSeconds(60));

        assertThat(Stream.of(early, more).sorted(RankingEntry.ORDER)).containsExactly(more, early);
    }

    @Test
    void 보조_값이_없는_판은_같은_값이면_먼저_도달한_쪽이_앞이다() {
        RankingEntry early = RankingEntry.of(2, "a", 3, T0);
        RankingEntry late = RankingEntry.of(1, "b", 3, T0.plusSeconds(60));

        assertThat(Stream.of(late, early).sorted(RankingEntry.ORDER)).containsExactly(early, late);
    }
}
