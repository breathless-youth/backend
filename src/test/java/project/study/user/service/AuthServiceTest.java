package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import project.study.studysession.repository.StudySessionRepository;
import project.study.user.dto.LinkSocialRequest;
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
    private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(Provider.GOOGLE, "sub-123", "test@gmail.com");

    @Mock
    private OAuthTokenVerifier tokenVerifier;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // link의 TransactionTemplate 실행용 — refresh/logout 테스트는 안 쓰므로 lenient
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        authService = new AuthService(
                List.of(tokenVerifier),
                userRepository,
                refreshTokenRepository,
                studySessionRepository,
                jwtUtil,
                transactionManager,
                REFRESH_EXPIRATION_MS);
    }

    @Test
    void refresh_토큰은_원문이_아닌_SHA256_해시로_저장된다() {
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        User device = new User(Provider.DEVICE, "device-uuid");
        ReflectionTestUtils.setField(device, "id", 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(device));
        when(userRepository.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Optional.empty());

        LoginResponse response = authService.linkSocialAccount(5L, new LinkSocialRequest(Provider.GOOGLE, ID_TOKEN));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
        assertThat(captor.getValue().getTokenHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void refresh는_기존_토큰을_사용_처리하고_새_토큰쌍을_발급한다() {
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(saved, "id", 10L);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(1);
        User user = new User(Provider.GOOGLE, "sub-123");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        TokenResponse response = authService.refresh(new RefreshRequest("refresh-uuid"));

        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        assertThat(response.refreshToken()).isNotEqualTo("refresh-uuid");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void 이미_사용_처리된_토큰이_오면_해당_유저의_토큰을_전부_폐기한다() {
        // 재사용이든 동시 요청 패배든, 조건부 UPDATE가 0을 반환하면 탈취 의심으로 처리
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(saved, "id", 10L);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(refreshTokenRepository, never()).save(any());
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
        verify(refreshTokenRepository, never()).markUsedIfUnused(anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void 이미_사용된_토큰은_만료됐어도_전량_폐기하고_거부한다() {
        // 만료 검사가 재사용 검사보다 앞서면 행만 삭제되고 끝나 재사용 증거(tombstone)가 사라진다
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().minusSeconds(1));
        ReflectionTestUtils.setField(saved, "id", 10L);
        ReflectionTestUtils.setField(saved, "usedAt", Instant.now().minusSeconds(10));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("이미 사용된 refresh 토큰입니다");
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(refreshTokenRepository, never()).markUsedIfUnused(anyLong(), any());
        verify(refreshTokenRepository, never()).delete(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void 로그아웃은_전달된_refresh_토큰의_해시로_삭제한다() {
        // 조건부 삭제가 1 = 미사용 토큰을 원자적으로 지웠다 → 해당 기기만 로그아웃
        when(refreshTokenRepository.deleteByTokenHashIfUnused(anyString())).thenReturn(1);

        authService.logout("refresh-uuid");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenRepository).deleteByTokenHashIfUnused(captor.capture());
        assertThat(captor.getValue()).isNotEqualTo("refresh-uuid");
        assertThat(captor.getValue()).hasSize(64); // SHA-256 hex
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
        // 원자적 삭제에 성공했으면 재조회할 이유가 없다
        verify(refreshTokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void 이미_사용된_토큰으로_로그아웃하면_해당_유저의_토큰을_전부_폐기한다() {
        // 탈취자가 회전시킨 뒤 그 토큰으로 logout하면 재사용 증거가 지워져 피해자의 재시도를 감지 못 한다
        RefreshToken used = new RefreshToken(7L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(used, "usedAt", Instant.now().minusSeconds(10));
        when(refreshTokenRepository.deleteByTokenHashIfUnused(anyString())).thenReturn(0);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(used));

        authService.logout("refresh-uuid");

        verify(refreshTokenRepository).deleteByUserId(7L);
    }

    @Test
    void 로그아웃_직전에_회전된_토큰도_전량_폐기된다() {
        // TOCTOU: 미사용 확인과 삭제 사이에 동시 refresh가 회전시키면, 조건부 삭제가 0을 반환한다.
        // 이때 그냥 지우면 used tombstone만 사라지고 새로 발급된 토큰이 살아남는다
        RefreshToken rotated = new RefreshToken(9L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(rotated, "usedAt", Instant.now());
        when(refreshTokenRepository.deleteByTokenHashIfUnused(anyString())).thenReturn(0);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(rotated));

        authService.logout("refresh-uuid");

        verify(refreshTokenRepository).deleteByUserId(9L);
    }

    @Test
    void 알_수_없는_토큰으로_로그아웃하면_아무것도_지우지_않는다() {
        when(refreshTokenRepository.deleteByTokenHashIfUnused(anyString())).thenReturn(0);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        authService.logout("unknown-token");

        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void 익명_유저의_link_전환은_isNewUser가_true다() {
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        User device = new User(Provider.DEVICE, "device-uuid");
        ReflectionTestUtils.setField(device, "id", 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(device));
        when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
                .thenReturn(Optional.empty());

        LoginResponse response = authService.linkSocialAccount(5L, new LinkSocialRequest(Provider.GOOGLE, ID_TOKEN));

        assertThat(response.isNewUser()).isTrue();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("5");
    }

    @Test
    void 전환은_새_토큰쌍_발급_전에_기존_refresh_토큰을_전량_폐기한다() {
        // 전환 전 익명 토큰은 userId가 그대로라 폐기하지 않으면 계속 유효하다
        when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
        when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
        User device = new User(Provider.DEVICE, "device-uuid");
        ReflectionTestUtils.setField(device, "id", 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(device));
        when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
                .thenReturn(Optional.empty());

        authService.linkSocialAccount(5L, new LinkSocialRequest(Provider.GOOGLE, ID_TOKEN));

        InOrder inOrder = inOrder(refreshTokenRepository);
        inOrder.verify(refreshTokenRepository).deleteByUserId(5L);
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
    }
}
