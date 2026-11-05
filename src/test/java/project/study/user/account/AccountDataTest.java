package project.study.user.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AccountDataTest {

    @Test
    void 자기_자신과는_병합하지_않는다() {
        // 남는 쪽과 옮기는 쪽이 같으면 처리기마다 "옮긴 뒤 옮기는 쪽 행 삭제"가 자기 데이터를 지운다
        UserDataHandler handler = mock(UserDataHandler.class);
        AccountData accountData = new AccountData(List.of(handler));

        assertThatThrownBy(() -> accountData.merge(7L, 7L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(handler);
    }

    @Test
    void 사용자_행보다_먼저_잡을_잠금은_처리기_순서대로_잡는다() {
        UserDataHandler later = mock(UserDataHandler.class);
        UserDataHandler earlier = mock(UserDataHandler.class);
        when(later.order()).thenReturn(260);
        when(earlier.order()).thenReturn(250);
        AccountData accountData = new AccountData(List.of(later, earlier));

        accountData.lockBeforeUsers(7L);

        InOrder order = inOrder(earlier, later);
        order.verify(earlier).lockBeforeUsers(7L);
        order.verify(later).lockBeforeUsers(7L);
    }
}
