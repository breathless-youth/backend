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

    /** 공동 순위 배치 — 값은 주어진 순서(내림차순), 같은 값은 앞 사람이 먼저 달성, myIndex 자리가 나다 */
    private static Placement tied(int myIndex, double... values) {
        List<RankingEntry> entries = IntStream.range(0, values.length)
                .mapToObj(i -> RankingEntry.of(i + 1, "u" + (i + 1), values[i], T0.plusSeconds(i)))
                .toList();
        return new Placement(entries, myIndex, true);
    }

    @Test
    void 공동_순위는_같은_값이면_같은_순위이고_다음은_인원만큼_건너뛴다() {
        Placement p = tied(3, 5, 5, 4, 4, 4, 2);

        assertThat(ranks(p.windowAround(2))).containsExactly(1, 1, 3, 3, 3);
        assertThat(ranks(p.windowAround(3))).containsExactly(1, 3, 3, 3, 6);
        assertThat(p.myRank()).isEqualTo(3);
        assertThat(ranks(p.podium())).containsExactly(1, 1, 3);
    }

    @Test
    void 공동_순위가_아니면_같은_값도_자리대로다() {
        Placement p = tied(3, 5, 5, 4, 4, 4, 2).withTies(false);

        assertThat(ranks(p.windowAround(3))).containsExactly(2, 3, 4, 5, 6);
        assertThat(p.myRank()).isEqualTo(4);
    }

    @Test
    void 공동_순위의_바로_위_아래는_값이_다른_가장_가까운_사람이다() {
        Placement p = tied(3, 5, 5, 4, 4, 4, 2);

        assertThat(p.above().userId()).isEqualTo(2);
        assertThat(p.below().userId()).isEqualTo(6);
    }

    @Test
    void 공동_순위의_맨_위_묶음이면_바로_위가_없고_맨_아래_묶음이면_바로_아래가_없다() {
        assertThat(tied(1, 5, 5, 4).above()).isNull();
        assertThat(tied(2, 5, 4, 4).below()).isNull();
    }

    @Test
    void 공동_순위의_상위_퍼센트는_공동_순위로_센다() {
        Placement p = tied(9, 10, 9, 8, 7, 1, 1, 1, 1, 1, 1);

        assertThat(p.myRank()).isEqualTo(5);
        assertThat(p.topPercent()).isEqualTo(50);
    }

    @Test
    void 값을_가정한_공동_순위는_그보다_큰_사람_수_더하기_1이다() {
        Placement p = tied(-1, 5, 3, 1, 1);

        assertThat(p.tiedRankOf(1)).isEqualTo(3);
        assertThat(p.tiedRankOf(6)).isEqualTo(1);
    }

    @Test
    void 내가_없어도_주어진_자리_주변_다섯_줄을_낸다() {
        assertThat(ranks(placement(10, -1).windowAround(9))).containsExactly(6, 7, 8, 9, 10);
        assertThat(ranks(placement(2, -1).windowAround(2))).containsExactly(1, 2);
    }
}
