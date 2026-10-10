package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TiersTest {

    @Test
    void 다음_구간은_내_상위_퍼센트보다_좁은_가장_가까운_구간이다() {
        assertThat(Tiers.next(7, 100)).hasValue(5);
        assertThat(Tiers.next(37, 100)).hasValue(30);
        assertThat(Tiers.next(55, 100)).hasValue(50);
    }

    @Test
    void 일퍼센트_안이면_다음_구간이_없다() {
        assertThat(Tiers.next(1, 100)).isEmpty();
    }

    @Test
    void 참가자가_적어_아무도_못_드는_구간은_건너뛴다() {
        assertThat(Tiers.next(15, 20)).hasValue(10);
        assertThat(Tiers.next(15, 9)).isEmpty();
    }

    @Test
    void 컷_순위는_내림이다() {
        assertThat(Tiers.cutoffRank(10, 25)).isEqualTo(2);
        assertThat(Tiers.cutoffRank(1, 99)).isZero();
    }
}
