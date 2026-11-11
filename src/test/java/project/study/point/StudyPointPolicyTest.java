package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.point.service.StudyPointPolicy;

class StudyPointPolicyTest {

    private final StudyPointPolicy policy = new StudyPointPolicy(1800, 10, 43200, LocalDate.of(2026, 11, 20));

    @Test
    void 삼십분에_못_미친_나머지는_버린다() {
        assertThat(policy.targetPoints(1799)).isZero();
        assertThat(policy.targetPoints(1800)).isEqualTo(10);
        assertThat(policy.targetPoints(4200)).isEqualTo(20);
    }

    @Test
    void 시작_날짜부터_센다() {
        assertThat(policy.counts(LocalDate.of(2026, 11, 19))).isFalse();
        assertThat(policy.counts(LocalDate.of(2026, 11, 20))).isTrue();
    }

    @Test
    void 하루_상한을_넘는_순공은_인정하지_않는다() {
        assertThat(policy.recognizedFocusSec(43199)).isEqualTo(43199);
        assertThat(policy.recognizedFocusSec(50000)).isEqualTo(43200);
        assertThat(policy.targetPoints(policy.recognizedFocusSec(50000))).isEqualTo(240);
    }
}
