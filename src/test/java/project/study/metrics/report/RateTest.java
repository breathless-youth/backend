package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateTest {

    @Test
    void 퍼센트는_정수_반올림이다() {
        assertThat(Rate.of(8, 14).percent()).isEqualTo(57);
        assertThat(Rate.of(1, 8).percent()).isEqualTo(13); // 12.5 → 13
        assertThat(Rate.of(0, 3).percent()).isZero();
    }

    @Test
    void 분모가_0이면_정의되지_않는다() {
        Rate empty = Rate.of(0, 0);

        assertThat(empty.defined()).isFalse();
        assertThat(empty.ratio()).isZero();
        assertThatThrownBy(empty::percent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 몇_퍼센트_이상인지_정수로_비교한다() {
        assertThat(Rate.of(3, 10).atLeastPercent(30)).isTrue();
        assertThat(Rate.of(29, 100).atLeastPercent(30)).isFalse();
        assertThat(Rate.of(0, 0).atLeastPercent(30)).isFalse();
    }
}
