package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CohortStatsTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);

    @Test
    void 세션당_일시정지_중앙값은_값이_있는_유저만으로_구한다() {
        CohortStats stats = CohortStats.of(List.of(
                user(1, D0).session(D0, 20, 30, 1, false).build(),
                user(2, D0).session(D0, 20, 30, 3, false).build(),
                user(3, D0).build())); // 세션 없음 — 빠진다

        assertThat(stats.medianPausesPerSession()).isEqualTo(2.0);
    }

    @Test
    void 홀수면_가운데_값이고_비어_있으면_NaN이다() {
        CohortStats odd = CohortStats.of(List.of(
                user(1, D0).session(D0, 20, 30, 1, false).build(),
                user(2, D0).session(D0, 20, 30, 5, false).build(),
                user(3, D0).session(D0, 20, 30, 2, false).build()));

        assertThat(odd.medianPausesPerSession()).isEqualTo(2.0);
        assertThat(CohortStats.of(List.of()).medianPausesPerSession()).isNaN();
    }
}
