package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String DEVICE_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private UserService userService;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        userService = new UserService(userRepository, transactionManager);
    }

    @Test
    void 새_기기_등록은_유저를_생성하고_isNew가_true다() {
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(userWithId(1L, DEVICE_ID));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.isNew()).isTrue();
    }

    @Test
    void 이미_등록된_기기는_기존_유저를_반환하고_isNew가_false다() {
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(7L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.isNew()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void deviceId는_소문자로_정규화되어_조회되고_저장된다() {
        String upperCase = DEVICE_ID.toUpperCase(java.util.Locale.ROOT);
        when(userRepository.findByProviderAndProviderUserId(eq(Provider.DEVICE), eq(DEVICE_ID)))
                .thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(userWithId(1L, DEVICE_ID));

        userService.register(new UserRegisterRequest(upperCase));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getProviderUserId()).isEqualTo(DEVICE_ID);
    }

    @Test
    void 동시_첫_등록_경쟁에서_지면_상대가_만든_유저를_반환한다() {
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.empty(), Optional.of(userWithId(3L, DEVICE_ID)));
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("중복 기기"));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(3L);
        assertThat(response.isNew()).isFalse();
    }

    private User userWithId(Long id, String deviceId) {
        User user = new User(Provider.DEVICE, deviceId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
