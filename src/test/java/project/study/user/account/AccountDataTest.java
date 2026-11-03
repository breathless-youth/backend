package project.study.user.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccountDataTest {

    @Test
    void 자기_자신과는_병합하지_않는다() {
        // 남는 쪽과 옮기는 쪽이 같으면 처리기마다 "옮긴 뒤 옮기는 쪽 행 삭제"가 자기 데이터를 지운다
        UserDataHandler handler = mock(UserDataHandler.class);
        AccountData accountData = new AccountData(List.of(handler));

        assertThatThrownBy(() -> accountData.merge(7L, 7L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(handler);
    }
}
