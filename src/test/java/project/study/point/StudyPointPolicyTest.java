package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.point.service.DailyRewardPolicy;
import project.study.point.service.StudyPointPolicy;
import project.study.point.service.StudyPointPolicy.Piece;

class StudyPointPolicyTest {

    private static final int HOUR = 3600;

    private final StudyPointPolicy policy = new StudyPointPolicy(
            1800, List.of(7200, 21600, 43200), List.of(7, 4, 1), List.of(10, 5, 2), LocalDate.of(2026, 11, 20));

    @Test
    void 삼십분에_못_미친_나머지는_버린다() {
        assertThat(policy.targetPoints(1799)).isZero();
        assertThat(policy.targetPoints(1800)).isEqualTo(7);
        assertThat(policy.targetPoints(4200)).isEqualTo(14);
    }

    @Test
    void 혼자_하면_구간이_넘어갈수록_적게_받는다() {
        assertThat(policy.targetPoints(2 * HOUR)).isEqualTo(28);
        assertThat(policy.targetPoints(6 * HOUR)).isEqualTo(28 + 32);
        assertThat(policy.targetPoints(12 * HOUR)).isEqualTo(28 + 32 + 12);
    }

    @Test
    void 같이_한_시간은_같이_값을_받고_다른_사람이_나간_뒤는_혼자_값이다() {
        // 처음 1시간은 같이, 다음 1시간은 혼자
        assertThat(policy.targetPoints(List.of(new Piece(HOUR, HOUR), new Piece(HOUR, 0))))
                .isEqualTo(20 + 14);
        // 혼자 2시간을 채운 뒤 같이 1시간이면 둘째 구간의 같이 값
        assertThat(policy.targetPoints(List.of(new Piece(2 * HOUR, 0), new Piece(HOUR, HOUR))))
                .isEqualTo(28 + 10);
        // 한 조각의 절반만 같이 했으면 시간 비율대로
        assertThat(policy.targetPoints(List.of(new Piece(HOUR, 1800)))).isEqualTo(10 + 7);
        // 비율은 끝까지 유지하고 하루 합계에서 한 번만 내림한다
        assertThat(policy.targetPoints(List.of(new Piece(9360, 720)))).isEqualTo(33);
        assertThat(policy.maxDailyPoints()).isEqualTo(40 + 40 + 24);
    }

    @Test
    void 시작_날짜부터_세고_하루_상한을_넘는_순공은_인정하지_않는다() {
        assertThat(policy.counts(LocalDate.of(2026, 11, 19))).isFalse();
        assertThat(policy.counts(LocalDate.of(2026, 11, 20))).isTrue();
        assertThat(policy.recognizedFocusSec(50000)).isEqualTo(43200);
        assertThat(policy.targetPoints(50000)).isEqualTo(72);
    }

    @Test
    void 구간_설정이_어긋나면_뜨지_않는다() {
        assertThatThrownBy(() -> new StudyPointPolicy(
                        1800, List.of(7200, 21600), List.of(7), List.of(10, 5), LocalDate.of(2026, 11, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 하루_목표는_처음_두_시간은_20P_그_뒤는_5P이고_여덟_시간까지만_센다() {
        DailyRewardPolicy reward = new DailyRewardPolicy(
                15,
                List.of(7200, 28800),
                List.of(20, 5),
                List.of(5, 3, 1),
                List.of(10, 5, 3),
                List.of(150, 100, 50),
                LocalDate.of(2000, 1, 1));

        assertThat(reward.goalPoints(null)).isZero();
        assertThat(reward.goalPoints(1799)).isZero();
        assertThat(reward.goalPoints(HOUR)).isEqualTo(40);
        assertThat(reward.goalPoints(2 * HOUR)).isEqualTo(80);
        assertThat(reward.goalPoints(3 * HOUR)).isEqualTo(90);
        assertThat(reward.goalPoints(8 * HOUR)).isEqualTo(140);
        assertThat(reward.goalPoints(12 * HOUR)).isEqualTo(140);
        assertThat(reward.medalPoints("MONTHLY", 1)).isEqualTo(150);
    }
}
