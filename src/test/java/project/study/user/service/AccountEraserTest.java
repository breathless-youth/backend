package project.study.user.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.user.account.AccountData;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

/** 탈퇴의 잠금 순서 — 그룹 쪽은 그룹 행을 잡은 뒤 users 행에 FK 잠금을 걸므로, 탈퇴도 그 행들을 users 행보다 먼저 잡아야 교착하지 않는다. */
@ExtendWith(MockitoExtension.class)
class AccountEraserTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountData accountData;

    @InjectMocks
    private AccountEraser accountEraser;

    @Test
    void 사용자_행을_잠그기_전에_다른_도메인의_잠금을_먼저_잡는다() {
        User user = new User(Provider.DEVICE, "device-7");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        accountEraser.erase(7L);

        InOrder order = inOrder(accountData, userRepository);
        order.verify(accountData).lockBeforeUsers(7L);
        order.verify(userRepository).findByIdForUpdate(7L);
        order.verify(accountData).erase(7L);
        order.verify(userRepository).delete(user);
    }
}
