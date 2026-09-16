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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.RefreshToken;
import project.study.user.entity.User;
import project.study.user.jwt.JwtUtil;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long REFRESH_EXPIRATION_MS = 1_209_600_000L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, jwtUtil, REFRESH_EXPIRATION_MS);
        // 유저 행 잠금은 모든 발급·회전 경로가 거친다 — 기본은 "유저 존재"
        User user = new User(Provider.DEVICE, "device-uuid");
        ReflectionTestUtils.setField(user, "id", 1L);
        lenient().when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(user));
        lenient()
                .when(refreshTokenRepository.findUserIdByTokenHash(anyString()))
                .thenReturn(Optional.of(1L));
    }

    @Test
    void refresh는_유저_행_잠금을_잡은_뒤에_토큰_행을_읽는다() {
        // 잠금 대기 중 다른 요청이 회전시킨 usedAt을 봐야 하므로 순서가 결과를 가른다
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(saved, "id", 10L);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(1);

        authService.refresh(new RefreshRequest("refresh-uuid"));

        InOrder inOrder = inOrder(userRepository, refreshTokenRepository);
        inOrder.verify(refreshTokenRepository).findUserIdByTokenHash(anyString());
        inOrder.verify(userRepository).findByIdForUpdate(1L);
        inOrder.verify(refreshTokenRepository).findByTokenHash(anyString());
    }

    @Test
    void 재발급과_폐기는_유저_행_잠금을_먼저_잡는다() {
        authService.issueTokensRevokingExisting(5L);

        InOrder inOrder = inOrder(userRepository, refreshTokenRepository);
        inOrder.verify(userRepository).findByIdForUpdate(5L);
        inOrder.verify(refreshTokenRepository).deleteByUserId(5L);
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refresh_토큰은_원문이_아닌_SHA256_해시로_저장된다() {
        AuthService.TokenPair tokens = authService.issueTokensRevokingExisting(5L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(tokens.refreshToken());
        assertThat(captor.getValue().getTokenHash()).hasSize(64); // SHA-256 hex
        assertThat(jwtUtil.getUserId(tokens.accessToken())).isEqualTo("5");
    }

    @Test
    void 재등록_발급은_기존_refresh를_전량_폐기한_뒤_새_쌍을_저장한다() {
        // DEVICE 유저 = 기기 하나. 재등록 전에 남아 있던 토큰(다른 곳에 복사된 것 포함)은 전부 무효가 돼야 한다
        authService.issueTokensRevokingExisting(5L);

        InOrder inOrder = inOrder(refreshTokenRepository);
        inOrder.verify(refreshTokenRepository).deleteByUserId(5L);
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refresh는_기존_토큰을_사용_처리하고_새_토큰쌍을_발급한다() {
        RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(saved, "id", 10L);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(1);

        TokenResponse response = authService.refresh(new RefreshRequest("refresh-uuid"));

        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        assertThat(response.refreshToken()).isNotEqualTo("refresh-uuid");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        // 회전은 tombstone(usedAt 마킹 행)을 남겨야 재사용을 감지한다 — 전량 폐기 경로가 아니다
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
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
        when(refreshTokenRepository.findUserIdByTokenHash(anyString())).thenReturn(Optional.empty());

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
    void 유저가_삭제된_뒤_남은_refresh_토큰으로는_재발급할_수_없다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).markUsedIfUnused(anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }
}
