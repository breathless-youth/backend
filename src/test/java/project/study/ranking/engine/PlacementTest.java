package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlacementTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    /** size명, 값은 내림차순, myIndex 자리가 나다(-1이면 나 없음) */
    private static Placement placement(int size, int myIndex) {
        List<RankingEntry> entries = IntStream.range(0, size)
                .mapToObj(i -> RankingEntry.of(i + 1, "u" + (i + 1), 100_000 - i, T0))
                .toList();
        return new Placement(entries, myIndex);
    }

    private static List<Integer> ranks(List<RankedEntry> rows) {
        return rows.stream().map(RankedEntry::rank).toList();
    }

    @Test
    void 가운데면_앞_2_나_뒤_2다() {
        assertThat(ranks(placement(10, 5).around())).containsExactly(4, 5, 6, 7, 8);
    }

    @Test
    void 일위면_나와_뒤_4명이고_바로_위가_없다() {
        Placement p = placement(10, 0);

        assertThat(ranks(p.around())).containsExactly(1, 2, 3, 4, 5);
        assertThat(p.above()).isNull();
        assertThat(p.below().userId()).isEqualTo(2);
    }

    @Test
    void 이위면_앞_1명과_뒤_3명이다() {
        assertThat(ranks(placement(10, 1).around())).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void 꼴찌면_앞_4명과_나고_바로_아래가_없다() {
        Placement p = placement(10, 9);

        assertThat(ranks(p.around())).containsExactly(6, 7, 8, 9, 10);
        assertThat(p.below()).isNull();
    }

    @Test
    void 참가자가_다섯보다_적으면_전부다() {
        Placement p = placement(3, 1);

        assertThat(ranks(p.around())).containsExactly(1, 2, 3);
        assertThat(ranks(p.podium())).containsExactly(1, 2, 3);
    }

    @Test
    void 혼자면_앞뒤가_없고_상위_100퍼센트다() {
        Placement p = placement(1, 0);

        assertThat(p.above()).isNull();
        assertThat(p.below()).isNull();
        assertThat(p.topPercent()).isEqualTo(100);
    }

    @Test
    void 상위_퍼센트는_올림이고_최소_1이다() {
        assertThat(placement(50, 0).topPercent()).isEqualTo(2);
        assertThat(placement(300, 0).topPercent()).isEqualTo(1);
        assertThat(placement(3257, 1204).topPercent()).isEqualTo(37);
    }

    @Test
    void 내가_없어도_주어진_자리_주변_다섯_줄을_낸다() {
        assertThat(ranks(placement(10, -1).windowAround(9))).containsExactly(6, 7, 8, 9, 10);
        assertThat(ranks(placement(2, -1).windowAround(2))).containsExactly(1, 2);
    }
}
