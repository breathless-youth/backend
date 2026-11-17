package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.point.service.PointTesters;
import project.study.user.service.MemberGuard;

class PointTestersTest {

    @Test
    void 설정에_적은_계정과_심사_계정만_테스트_계정이다() {
        MemberGuard members = mock(MemberGuard.class);
        given(members.isReviewAccount(30L)).willReturn(true);
        PointTesters testers = new PointTesters(members, List.of(10L, 20L));

        assertThat(testers.isTester(10L)).isTrue();
        assertThat(testers.isTester(30L)).isTrue();
        assertThat(testers.isTester(40L)).isFalse();
        assertThat(new PointTesters(members, List.of()).isTester(10L)).isFalse();
    }
}
