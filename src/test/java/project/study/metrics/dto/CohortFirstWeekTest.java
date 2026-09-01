package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CohortFirstWeekTest {

    @Test
    void 첫주_공부일수의_평균을_낸다() {
        // 3명이 각각 4일, 3일, 5일 → 평균 4.0일
        CohortFirstWeek result = CohortFirstWeek.from(List.of(4L, 3L, 5L));

        assertThat(result.cohortSize()).isEqualTo(3);
        assertThat(result.avgDays()).isEqualTo(4.0);
    }

    @Test
    void 코호트가_비면_크기와_평균이_0이다() {
        CohortFirstWeek result = CohortFirstWeek.from(List.of());

        assertThat(result.cohortSize()).isZero();
        assertThat(result.avgDays()).isZero();
    }

    @Test
    void 평균은_나누어떨어지지_않아도_소수로_유지한다() {
        CohortFirstWeek result = CohortFirstWeek.from(List.of(1L, 2L));

        assertThat(result.avgDays()).isEqualTo(1.5);
    }
}
