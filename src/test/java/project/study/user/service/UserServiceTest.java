package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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

    @InjectMocks
    private UserService userService;

    @Test
    void 새_기기_등록은_유저를_생성하고_isNew가_true다() {
        when(userRepository.insertIfAbsent(Provider.DEVICE.name(), DEVICE_ID)).thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.isNew()).isTrue();
    }

    @Test
    void 이미_등록된_기기는_기존_유저를_반환하고_isNew가_false다() {
        // on conflict do nothing → 0행 삽입 = 이미 등록된 기기
        when(userRepository.insertIfAbsent(Provider.DEVICE.name(), DEVICE_ID)).thenReturn(0);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(7L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.isNew()).isFalse();
    }

    @Test
    void deviceId는_소문자로_정규화되어_저장되고_조회된다() {
        String upperCase = DEVICE_ID.toUpperCase(Locale.ROOT);
        when(userRepository.insertIfAbsent(Provider.DEVICE.name(), DEVICE_ID)).thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        userService.register(new UserRegisterRequest(upperCase));

        verify(userRepository).insertIfAbsent(Provider.DEVICE.name(), DEVICE_ID);
        verify(userRepository).findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID);
    }

    @Test
    void 등록_직후_조회가_실패하면_IllegalStateException을_던진다() {
        when(userRepository.insertIfAbsent(Provider.DEVICE.name(), DEVICE_ID)).thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.register(new UserRegisterRequest(DEVICE_ID)))
                .isInstanceOf(IllegalStateException.class);
    }

    private User userWithId(Long id, String deviceId) {
        User user = new User(Provider.DEVICE, deviceId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
