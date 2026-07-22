package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.user.dto.LoginRequest;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.RefreshToken;
import project.study.user.entity.User;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.OAuthTokenVerifier;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String ID_TOKEN = "google-id-token";
    private static final long REFRESH_EXPIRATION_MS = 1_209_600_000L;
    private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(Provider.GOOGLE, "sub-123", "a@b.com");

    @Mock
    private OAuthTokenVerifier tokenVerifier;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                List.of(tokenVerifier), userRepository, refreshTokenRepository, jwtUtil, REFRESH_EXPIRATION_MS);
    }

    @Test
    void 신규_유저_로그인은_유저를_생성하고_isNewUser가_true다() {
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });

        LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));

        assertThat(response.isNewUser()).isTrue();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void 기존_유저_로그인은_유저를_생성하지_않고_isNewUser가_false다() {
        User existing = new User(Provider.GOOGLE, "sub-123");
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
                .thenReturn(Optional.of(existing));

        LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));

        assertThat(response.isNewUser()).isFalse();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("7");
        verify(userRepository, never()).save(any());
    }

    @Test
    void refresh_토큰은_원문이_아닌_SHA256_해시로_저장된다() {
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        when(userRepository.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });

        LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
        assertThat(captor.getValue().getTokenHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void refresh는_기존_토큰을_사용_처리하고_새_토큰쌍을_발급한다() {
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        TokenResponse response = authService.refresh(new RefreshRequest("refresh-uuid"));

        assertThat(saved.isUsed()).isTrue();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        assertThat(response.refreshToken()).isNotEqualTo("refresh-uuid");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void 사용된_refresh_토큰이_다시_오면_해당_유저의_토큰을_전부_폐기한다() {
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        saved.markUsed();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository).deleteByUserId(1L);
    }

    @Test
    void 알_수_없는_refresh_토큰은_거부하되_폐기는_하지_않는다() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void 만료된_refresh_토큰은_삭제하고_거부한다() {
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository).delete(saved);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void 로그아웃은_전달된_refresh_토큰의_해시로_삭제한다() {
        authService.logout("refresh-uuid");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenRepository).deleteByTokenHash(captor.capture());
        assertThat(captor.getValue()).isNotEqualTo("refresh-uuid");
        assertThat(captor.getValue()).hasSize(64); // SHA-256 hex
    }
}
