package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.common.BadRequestException;
import project.study.common.ConflictException;
import project.study.user.dto.ProfileResponse;
import project.study.user.dto.ProfileUpdateRequest;
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
        when(userRepository.insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt()))
                .thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.isNew()).isTrue();
    }

    @Test
    void 등록_시_자동_닉네임은_포메_뒤에_4자리_숫자다() {
        when(userRepository.insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt()))
                .thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        userService.register(new UserRegisterRequest(DEVICE_ID));

        ArgumentCaptor<String> nicknameCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository)
                .insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), nicknameCaptor.capture(), eq("포"), anyInt());
        assertThat(nicknameCaptor.getValue()).matches("포메\\d{4}");
    }

    @Test
    void 이미_등록된_기기는_기존_유저를_반환하고_isNew가_false다() {
        when(userRepository.insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt()))
                .thenReturn(0);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(7L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.isNew()).isFalse();
    }

    @Test
    void 자동_닉네임이_충돌하면_새_닉네임으로_재시도한다() {
        // 0행 + 기기 없음 = 닉네임 충돌 → 재시도, 두 번째에 성공
        when(userRepository.insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt()))
                .thenReturn(0, 1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        UserRegisterResponse response = userService.register(new UserRegisterRequest(DEVICE_ID));

        assertThat(response.isNew()).isTrue();
    }

    @Test
    void deviceId는_소문자로_정규화되어_저장되고_조회된다() {
        String upperCase = DEVICE_ID.toUpperCase(Locale.ROOT);
        when(userRepository.insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt()))
                .thenReturn(1);
        when(userRepository.findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID))
                .thenReturn(Optional.of(userWithId(1L, DEVICE_ID)));

        userService.register(new UserRegisterRequest(upperCase));

        verify(userRepository)
                .insertIfAbsent(eq(Provider.DEVICE.name()), eq(DEVICE_ID), anyString(), eq("포"), anyInt());
        verify(userRepository).findByProviderAndProviderUserId(Provider.DEVICE, DEVICE_ID);
    }

    @Test
    void 프로필_수정은_보낸_필드만_반영한다() {
        User user = userWithId(1L, DEVICE_ID);
        user.updateProfile("기존닉네임", "기존 목표", "JOB");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ProfileResponse response = userService.updateProfile(1L, new ProfileUpdateRequest(null, "새 목표", null));

        assertThat(response.nickname()).isEqualTo("기존닉네임");
        assertThat(response.goal()).isEqualTo("새 목표");
        assertThat(response.category()).isEqualTo("JOB");
    }

    @Test
    void 닉네임_변경_시_이니셜이_갱신된다() {
        User user = userWithId(1L, DEVICE_ID);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("숨벅찬청년들")).thenReturn(false);

        ProfileResponse response = userService.updateProfile(1L, new ProfileUpdateRequest("숨벅찬청년들", null, null));

        assertThat(response.nickname()).isEqualTo("숨벅찬청년들");
        assertThat(response.initial()).isEqualTo("숨");
    }

    @Test
    void 사용_중인_닉네임으로_변경하면_409다() {
        User user = userWithId(1L, DEVICE_ID);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("사용중닉네임")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest("사용중닉네임", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 닉네임_형식이_틀리면_400이다() {
        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest("한", null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest("특수문자!!", null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest("열세글자가넘는닉네임이다열세", null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 목표가_20자를_넘으면_400이다() {
        assertThatThrownBy(() ->
                        userService.updateProfile(1L, new ProfileUpdateRequest(null, "스물한 글자가 넘는 아주 길고 긴 목표 문구", null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 정의되지_않은_카테고리는_400이다() {
        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest(null, null, "UNKNOWN")))
                .isInstanceOf(BadRequestException.class);
    }

    private User userWithId(Long id, String deviceId) {
        User user = new User(Provider.DEVICE, deviceId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
