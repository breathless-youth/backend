package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.point.service.ChargePolicy;

class ChargePolicyTest {

    @Test
    void 값이_비었거나_자리표시_값이면_충전은_꺼져_있다() {
        assertThat(new ChargePolicy("", false).available()).isFalse();
        assertThat(new ChargePolicy(" CHANGE_ME ", false).available()).isFalse();
        assertThat(new ChargePolicy("CHANGE_ME", false).authorized("CHANGE_ME")).isFalse();
    }

    @Test
    void 설정한_값과_같은_Authorization만_받는다() {
        ChargePolicy policy = new ChargePolicy("real-value", false);

        assertThat(policy.available()).isTrue();
        assertThat(policy.authorized("real-value")).isTrue();
        assertThat(policy.authorized("CHANGE_ME")).isFalse();
        assertThat(policy.authorized(null)).isFalse();
    }
}
