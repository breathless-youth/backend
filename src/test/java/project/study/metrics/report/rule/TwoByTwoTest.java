package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;

class TwoByTwoTest {

    @Test
    void phi는_2x2_표의_상관이다() {
        TwoByTwo table = new TwoByTwo(8, 6, 8, 230);

        assertThat(table.phi()).isCloseTo(0.5052, within(1e-4));
        assertThat(table.doerRetention()).isEqualTo(Rate.of(8, 14));
        assertThat(table.nonDoerRetention()).isEqualTo(Rate.of(8, 238));
        assertThat(table.exposure()).isEqualTo(Exposure.PASS);
    }

    @Test
    void 한_쪽이라도_5명_미만이면_사람_부족이다() {
        assertThat(new TwoByTwo(4, 0, 1, 95).exposure()).isEqualTo(Exposure.TOO_FEW);
        assertThat(new TwoByTwo(50, 45, 0, 4).exposure()).isEqualTo(Exposure.TOO_FEW);
        assertThat(new TwoByTwo(5, 0, 3, 92).exposure()).isEqualTo(Exposure.PASS);
    }

    @Test
    void 한_사람이_90퍼센트를_넘으면_너무_흔하다() {
        assertThat(new TwoByTwo(5, 50, 0, 5).exposure()).isEqualTo(Exposure.TOO_COMMON); // 55/60
        assertThat(new TwoByTwo(20, 34, 0, 6).exposure()).isEqualTo(Exposure.PASS); // 54/60 = 90%, phi 0.24
    }

    @Test
    void 상관이_0_2_미만이거나_계산할_수_없으면_약하다() {
        assertThat(new TwoByTwo(1, 9, 1, 9).exposure()).isEqualTo(Exposure.WEAK);
        assertThat(new TwoByTwo(0, 10, 0, 10).phi()).isNaN();
        assertThat(new TwoByTwo(0, 10, 0, 10).exposure()).isEqualTo(Exposure.WEAK);
    }

    @Test
    void 강도는_Compass_구간을_따른다() {
        assertThat(Strength.of(0.4)).isEqualTo(Strength.STRONG);
        assertThat(Strength.of(0.39)).isEqualTo(Strength.MODERATE);
        assertThat(Strength.of(0.3)).isEqualTo(Strength.MODERATE);
        assertThat(Strength.of(0.2)).isEqualTo(Strength.WEAK);
        assertThat(Strength.of(0.19)).isEqualTo(Strength.NONE);
        assertThat(Strength.of(Double.NaN)).isEqualTo(Strength.NONE);
    }
}
