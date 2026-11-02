package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import project.study.user.dto.UserRegisterRequest;

class DeviceRegistrationServiceTest {

    private static final String DEVICE_ID = "8f14e45f-ceea-467e-a2b8-6a1b8b2c3d4e";
    private static final UserRegisterRequest REQUEST = new UserRegisterRequest(DEVICE_ID);
    private static final AuthService.TokenPair TOKENS = new AuthService.TokenPair("access", "refresh");

    private final UserService userService = mock(UserService.class);
    private final AuthService authService = mock(AuthService.class);
    private final DeviceRegistrationService registration = new DeviceRegistrationService(userService, authService);

    @Test
    void 그대로_비회원이면_한_번에_등록하고_발급한다() {
        when(userService.register(REQUEST)).thenReturn(new UserService.RegisterResult(1L, false, DEVICE_ID));
        when(authService.issueDeviceTokens(1L, DEVICE_ID)).thenReturn(Optional.of(TOKENS));

        DeviceRegistrationService.Registration result = registration.register(REQUEST);

        assertThat(result.isNew()).isFalse();
        assertThat(result.tokens()).isEqualTo(TOKENS);
        verify(userService, times(1)).register(REQUEST);
    }

    @Test
    void 등록과_발급_사이에_소셜_계정이_됐으면_다시_등록해_새_비회원에게_발급한다() {
        when(userService.register(REQUEST))
                .thenReturn(
                        new UserService.RegisterResult(1L, false, DEVICE_ID),
                        new UserService.RegisterResult(2L, true, DEVICE_ID));
        when(authService.issueDeviceTokens(1L, DEVICE_ID)).thenReturn(Optional.empty());
        when(authService.issueDeviceTokens(2L, DEVICE_ID)).thenReturn(Optional.of(TOKENS));

        DeviceRegistrationService.Registration result = registration.register(REQUEST);

        assertThat(result.isNew()).isTrue();
        assertThat(result.tokens()).isEqualTo(TOKENS);
    }

    @Test
    void 다시_등록한_비회원도_바로_바뀌었으면_한_번만_더_하고_실패한다() {
        when(userService.register(REQUEST))
                .thenReturn(
                        new UserService.RegisterResult(1L, false, DEVICE_ID),
                        new UserService.RegisterResult(2L, true, DEVICE_ID));
        when(authService.issueDeviceTokens(1L, DEVICE_ID)).thenReturn(Optional.empty());
        when(authService.issueDeviceTokens(2L, DEVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registration.register(REQUEST)).isInstanceOf(IllegalStateException.class);
        verify(userService, times(2)).register(REQUEST);
    }
}
