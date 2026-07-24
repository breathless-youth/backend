package project.study.user.service;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyLong;
// import static org.mockito.ArgumentMatchers.anyString;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.lenient;
// import static org.mockito.Mockito.never;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;
//
// import java.time.Instant;
// import java.util.List;
// import java.util.Optional;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.dao.DataIntegrityViolationException;
// import org.springframework.test.util.ReflectionTestUtils;
// import org.springframework.transaction.PlatformTransactionManager;
// import org.springframework.transaction.support.SimpleTransactionStatus;
// import project.study.user.dto.LoginRequest;
// import project.study.user.dto.LoginResponse;
// import project.study.user.dto.RefreshRequest;
// import project.study.user.dto.TokenResponse;
// import project.study.user.entity.Provider;
// import project.study.user.entity.RefreshToken;
// import project.study.user.entity.User;
// import project.study.user.jwt.JwtUtil;
// import project.study.user.oauth.OAuthTokenVerifier;
// import project.study.user.oauth.OAuthUserInfo;
// import project.study.user.repository.RefreshTokenRepository;
// import project.study.user.repository.UserRepository;
//
// @ExtendWith(MockitoExtension.class)
// class AuthServiceTest {
//
//     private static final String ID_TOKEN = "google-id-token";
//     private static final long REFRESH_EXPIRATION_MS = 1_209_600_000L;
//     private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(Provider.GOOGLE, "sub-123");
//
//     @Mock
//     private OAuthTokenVerifier tokenVerifier;
//
//     @Mock
//     private UserRepository userRepository;
//
//     @Mock
//     private RefreshTokenRepository refreshTokenRepository;
//
//     @Mock
//     private PlatformTransactionManager transactionManager;
//
//     private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);
//
//     private AuthService authService;
//
//     @BeforeEach
//     void setUp() {
//         // login의 TransactionTemplate 실행용 — refresh/logout 테스트는 안 쓰므로 lenient
//         lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
//         authService = new AuthService(
//                 List.of(tokenVerifier),
//                 userRepository,
//                 refreshTokenRepository,
//                 jwtUtil,
//                 transactionManager,
//                 REFRESH_EXPIRATION_MS);
//     }
//
//     @Test
//     void 신규_유저_로그인은_유저를_생성하고_isNewUser가_true다() {
//         when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
//         when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
//         when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
//                 .thenReturn(Optional.empty());
//         when(userRepository.save(any())).thenAnswer(invocation -> {
//             User user = invocation.getArgument(0);
//             ReflectionTestUtils.setField(user, "id", 1L);
//             return user;
//         });
//
//         LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));
//
//         assertThat(response.isNewUser()).isTrue();
//         assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
//         verify(refreshTokenRepository).save(any(RefreshToken.class));
//     }
//
//     @Test
//     void 기존_유저_로그인은_유저를_생성하지_않고_isNewUser가_false다() {
//         User existing = new User(Provider.GOOGLE, "sub-123");
//         ReflectionTestUtils.setField(existing, "id", 7L);
//         when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
//         when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
//         when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
//                 .thenReturn(Optional.of(existing));
//
//         LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));
//
//         assertThat(response.isNewUser()).isFalse();
//         assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("7");
//         verify(userRepository, never()).save(any());
//     }
//
//     @Test
//     void refresh_토큰은_원문이_아닌_SHA256_해시로_저장된다() {
//         when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
//         when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
//         when(userRepository.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Optional.empty());
//         when(userRepository.save(any())).thenAnswer(invocation -> {
//             User user = invocation.getArgument(0);
//             ReflectionTestUtils.setField(user, "id", 1L);
//             return user;
//         });
//
//         LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));
//
//         ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
//         verify(refreshTokenRepository).save(captor.capture());
//         assertThat(captor.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
//         assertThat(captor.getValue().getTokenHash()).hasSize(64); // SHA-256 hex
//     }
//
//     @Test
//     void refresh는_기존_토큰을_사용_처리하고_새_토큰쌍을_발급한다() {
//         RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
//         ReflectionTestUtils.setField(saved, "id", 10L);
//         when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
//         when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(1);
//
//         TokenResponse response = authService.refresh(new RefreshRequest("refresh-uuid"));
//
//         assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
//         assertThat(response.refreshToken()).isNotEqualTo("refresh-uuid");
//         verify(refreshTokenRepository).save(any(RefreshToken.class));
//     }
//
//     @Test
//     void 이미_사용_처리된_토큰이_오면_해당_유저의_토큰을_전부_폐기한다() {
//         // 재사용이든 동시 요청 패배든, 조건부 UPDATE가 0을 반환하면 탈취 의심으로 처리
//         RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().plusSeconds(3600));
//         ReflectionTestUtils.setField(saved, "id", 10L);
//         when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
//         when(refreshTokenRepository.markUsedIfUnused(eq(10L), any())).thenReturn(0);
//
//         assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
//                 .isInstanceOf(InvalidRefreshTokenException.class);
//         verify(refreshTokenRepository).deleteByUserId(1L);
//         verify(refreshTokenRepository, never()).save(any());
//     }
//
//     @Test
//     void 알_수_없는_refresh_토큰은_거부하되_폐기는_하지_않는다() {
//         when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
//
//         assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-token")))
//                 .isInstanceOf(InvalidRefreshTokenException.class);
//         verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
//     }
//
//     @Test
//     void 만료된_refresh_토큰은_삭제하고_거부한다() {
//         RefreshToken saved = new RefreshToken(1L, "hash", Instant.now().minusSeconds(1));
//         when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
//
//         assertThatThrownBy(() -> authService.refresh(new RefreshRequest("refresh-uuid")))
//                 .isInstanceOf(InvalidRefreshTokenException.class);
//         verify(refreshTokenRepository).delete(saved);
//         verify(refreshTokenRepository, never()).markUsedIfUnused(anyLong(), any());
//         verify(refreshTokenRepository, never()).save(any());
//     }
//
//     @Test
//     void 동시_첫_로그인_경쟁에서_지면_상대가_만든_유저로_재시도한다() {
//         User winner = new User(Provider.GOOGLE, "sub-123");
//         ReflectionTestUtils.setField(winner, "id", 3L);
//         when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
//         when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
//         when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
//                 .thenReturn(Optional.empty(), Optional.of(winner));
//         when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("중복 유저"));
//
//         LoginResponse response = authService.login(new LoginRequest(Provider.GOOGLE, ID_TOKEN));
//
//         assertThat(response.isNewUser()).isFalse();
//         assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("3");
//         // 재시도는 DB 트랜잭션만 다시 실행 — 구글 검증을 반복하지 않는다
//         verify(tokenVerifier).verify(ID_TOKEN);
//     }
//
//     @Test
//     void 로그아웃은_전달된_refresh_토큰의_해시로_삭제한다() {
//         authService.logout("refresh-uuid");
//
//         ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
//         verify(refreshTokenRepository).deleteByTokenHash(captor.capture());
//         assertThat(captor.getValue()).isNotEqualTo("refresh-uuid");
//         assertThat(captor.getValue()).hasSize(64); // SHA-256 hex
//     }
// }
