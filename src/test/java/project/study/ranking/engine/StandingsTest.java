package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandingsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    private static RankingEntry e(long id, double value) {
        return RankingEntry.of(id, "u" + id, value, T0);
    }

    private static RankingEntry e(long id, double value, Instant achievedAt) {
        return RankingEntry.of(id, "u" + id, value, achievedAt);
    }

    @Test
    void 값이_크면_앞이고_같으면_먼저_도달한_사람_그다음_userId가_앞이다() {
        Standings s = Standings.of(List.of(e(3, 100, T0.plusSeconds(5)), e(4, 100, T0), e(1, 200), e(2, 100, T0)), T0);

        assertThat(s.entries()).extracting(RankingEntry::userId).containsExactly(1L, 2L, 4L, 3L);
    }

    @Test
    void 나를_끼우면_순위표에_남은_내_옛_줄은_새_값으로_대신한다() {
        Placement p = Standings.of(List.of(e(1, 300), e(2, 200), e(3, 100)), T0).place(e(3, 250));

        assertThat(p.merged()).extracting(RankingEntry::userId).containsExactly(1L, 3L, 2L);
        assertThat(p.myRank()).isEqualTo(2);
        assertThat(p.me().value()).isEqualTo(250);
    }

    @Test
    void 내가_없으면_남들만_있고_내_순위는_없다() {
        Placement p = Standings.of(List.of(e(1, 300), e(2, 200)), T0).place(null);

        assertThat(p.present()).isFalse();
        assertThat(p.size()).isEqualTo(2);
        assertThat(p.me()).isNull();
        assertThat(p.around()).isEmpty();
    }

    @Test
    void 집중_중인_줄은_요청_시각까지_올라가_순위가_바뀔_수_있다() {
        RankingEntry focusing = new RankingEntry(1, "u1", 100, T0, true, 0, 0);

        Standings s = Standings.of(List.of(focusing, e(2, 105)), T0).advancedTo(T0.plusSeconds(10));

        assertThat(s.entries()).extracting(RankingEntry::userId).containsExactly(1L, 2L);
        assertThat(s.entries().getFirst().value()).isEqualTo(110);
        assertThat(s.asOf()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void 마감된_순위표는_마감_시각까지만_올리고_모든_줄의_집중_중_표시를_끈다() {
        RankingEntry focusing = new RankingEntry(1, "u1", 100, T0, true, 0, 0);
        Instant closesAt = T0.plusSeconds(4);

        Standings s = Standings.of(List.of(focusing, e(2, 105)), T0).settledAt(closesAt);

        assertThat(s.entries()).extracting(RankingEntry::userId).containsExactly(2L, 1L);
        assertThat(s.entries()).extracting(RankingEntry::value).containsExactly(105.0, 104.0);
        assertThat(s.entries()).noneMatch(RankingEntry::focusing);
        assertThat(s.asOf()).isEqualTo(closesAt);
    }

    @Test
    void 기준_시각이_이미_마감_뒤인_순위표는_뒤로_돌리지_않는다() {
        RankingEntry focusing = new RankingEntry(1, "u1", 100, T0, true, 0, 0);
        Instant asOf = T0.plusSeconds(10);

        Standings s = Standings.of(List.of(focusing, e(2, 99)), asOf).settledAt(T0.plusSeconds(4));

        assertThat(s.entries()).extracting(RankingEntry::value).containsExactly(100.0, 99.0);
        assertThat(s.entries()).noneMatch(RankingEntry::focusing);
        assertThat(s.asOf()).isEqualTo(asOf);
    }

    @Test
    void 가정한_값이_받을_순위를_낸다() {
        Standings s = Standings.of(List.of(e(1, 300), e(2, 200), e(3, 100)), T0);

        assertThat(s.rankOf(e(9, 150))).isEqualTo(3);
        assertThat(s.rankOf(e(2, 400))).isEqualTo(1);
    }
}
