package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import project.study.room.service.CurrentRoomLeaver;
import project.study.user.account.AccountData;
import project.study.user.dto.LinkResponse;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.image.ProfileImageStorage;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.oauth.SocialTokenVerifiers;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UsedSocialIdTokenRepository;
import project.study.user.repository.UserRepository;

/** link의 트랜잭션 경계·재시도·잠금 순서·방 나가기 시점. DB가 실제로 어떻게 움직이는지는 LinkApiTest·LinkRoomApiTest가 본다. */
@ExtendWith(MockitoExtension.class)
class AuthServiceLinkTest {

    private static final LinkSocialRequest REQUEST = new LinkSocialRequest(Provider.GOOGLE, "id-token", null);
    private static final String IMAGE = "avatars/1/8f14e45f-ceea-467e-a2b8-6a1b8b2c3d4e.jpg";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private SocialTokenVerifiers verifiers;

    @Mock
    private CurrentRoomLeaver currentRoomLeaver;

    @Mock
    private AccountData accountData;

    @Mock
    private ProfileImageStorage profileImageStorage;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private UsedSocialIdTokenRepository usedSocialIdTokens;

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                new TokenIssuer(jwtUtil, refreshTokenRepository, 1_209_600_000L),
                verifiers,
                currentRoomLeaver,
                transactionManager,
                new AccountLinker(
                        userRepository, refreshTokenRepository, accountData, profileImageStorage, usedSocialIdTokens));
        lenient()
                .when(verifiers.verify(Provider.GOOGLE, "id-token"))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, "g-sub", "a@example.com", null, null));
        lenient().when(usedSocialIdTokens.markUsed(anyString(), any())).thenReturn(true);
    }

    @Test
    void 병합이면_검증_뒤_트랜잭션_밖에서_방을_먼저_나간다() {
        givenDevice(1L, null);
        givenSocialUser(2L);

        authService.link(1L, REQUEST);

        InOrder order = inOrder(verifiers, currentRoomLeaver, transactionManager);
        order.verify(verifiers).verify(Provider.GOOGLE, "id-token");
        order.verify(currentRoomLeaver).leave(1L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    void 전환이면_방에서_빼지_않고_새_트랜잭션_하나로_끝낸다() {
        givenDevice(1L, null);
        givenOwner(Optional.empty());

        authService.link(1L, REQUEST);

        verifyNoInteractions(currentRoomLeaver);
        verify(transactionManager)
                .getTransaction(argThat(definition ->
                        definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void 이미_소셜_계정이면_방에서_빼지_않고_409다() {
        // 그 소셜 ID는 다른 계정 것이라 미리 보기만으로는 병합처럼 보이지만, 옮기는 쪽이 비회원이 아니다
        when(userRepository.findProviderById(1L)).thenReturn(Optional.of(Provider.KAKAO));
        givenSocialUser(2L);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L, Provider.KAKAO)));

        assertThatThrownBy(() -> authService.link(1L, REQUEST)).isInstanceOf(AlreadyLinkedException.class);
        verifyNoInteractions(currentRoomLeaver);
        // 그룹 행 잠금은 users 행을 잡기 전이라 이 판정보다 먼저 일어난다 — 병합만 하지 않으면 된다(롤백으로 풀린다)
        verify(accountData, never()).merge(anyLong(), anyLong());
    }

    @Test
    void 소셜_ID의_주인이_나_자신이면_잠그지도_병합하지도_않고_409다() {
        when(userRepository.findProviderById(1L)).thenReturn(Optional.of(Provider.GOOGLE));
        givenOwner(Optional.of(1L));

        assertThatThrownBy(() -> authService.link(1L, REQUEST)).isInstanceOf(AlreadyLinkedException.class);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(currentRoomLeaver, accountData);
    }

    @Test
    void nonce가_틀리면_방에서_빼지_않고_트랜잭션도_열지_않는다() {
        when(verifiers.verify(Provider.APPLE, "apple-token"))
                .thenReturn(new OAuthUserInfo(Provider.APPLE, "a-sub", null, "not-the-hash", null));

        assertThatThrownBy(() -> authService.link(1L, new LinkSocialRequest(Provider.APPLE, "apple-token", "raw")))
                .isInstanceOf(InvalidOAuthTokenException.class);
        verifyNoInteractions(currentRoomLeaver, transactionManager, userRepository);
    }

    @Test
    void 이미_쓴_ID_토큰이면_트랜잭션_첫_문장에서_400이고_잠그지도_바꾸지도_않는다() {
        givenDevice(1L, null);
        givenOwner(Optional.empty());
        when(usedSocialIdTokens.markUsed(eq(TokenIssuer.sha256("id-token")), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.link(1L, REQUEST)).isInstanceOf(InvalidOAuthTokenException.class);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(userRepository, never()).flush();
        verify(transactionManager).rollback(any());
        verifyNoInteractions(accountData, refreshTokenRepository);
    }

    @Test
    void 전환_경쟁_재시도도_같은_토큰을_다시_사용_처리한다() {
        givenDevice(1L, null);
        when(userRepository.findIdByProviderAndProviderUserId(Provider.GOOGLE, "g-sub"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(2L));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, Provider.GOOGLE)));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .doNothing()
                .when(userRepository)
                .flush();

        authService.link(1L, REQUEST);

        // 첫 시도의 사용 처리는 그 트랜잭션과 함께 롤백되므로 재시도 트랜잭션에서 다시 한다
        verify(usedSocialIdTokens, times(2)).markUsed(eq(TokenIssuer.sha256("id-token")), any());
    }

    @Test
    void 전환은_옮기는_쪽_refresh를_전량_지운_뒤_새_쌍을_발급하고_사진은_지우지_않는다() {
        givenDevice(1L, IMAGE);
        givenOwner(Optional.empty());

        LinkResponse response = authService.link(1L, REQUEST);

        assertThat(response.isNewUser()).isTrue();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        InOrder order = inOrder(userRepository, refreshTokenRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(userRepository).flush();
        order.verify(refreshTokenRepository).deleteByUserId(1L);
        order.verify(refreshTokenRepository).save(argThat(t -> t.getUserId() == 1L));
        verifyNoInteractions(accountData, profileImageStorage);
    }

    @Test
    void 전환_flush가_unique_위반이면_방에서_뺀_뒤_새_트랜잭션에서_병합_경로로_한_번_더_한다() {
        givenDevice(1L, null);
        // 미리 보기·첫 시도는 비어 있다가, 다른 기기가 먼저 전환해 재시도에서는 보인다
        when(userRepository.findIdByProviderAndProviderUserId(Provider.GOOGLE, "g-sub"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(2L));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, Provider.GOOGLE)));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .doNothing()
                .when(userRepository)
                .flush();

        LinkResponse response = authService.link(1L, REQUEST);

        assertThat(response.isNewUser()).isFalse();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("2");
        verify(transactionManager, times(2)).getTransaction(any());
        InOrder order = inOrder(transactionManager, currentRoomLeaver, accountData);
        order.verify(transactionManager).rollback(any());
        order.verify(currentRoomLeaver).leave(1L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(accountData).merge(1L, 2L);
        order.verify(transactionManager).commit(any());
        verify(currentRoomLeaver, times(1)).leave(1L);
    }

    @Test
    void 전환_경쟁_재시도는_한_번뿐이다() {
        givenDevice(1L, null);
        givenOwner(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(userRepository)
                .flush();

        assertThatThrownBy(() -> authService.link(1L, REQUEST))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void 병합은_두_사용자_행을_ID_작은_쪽부터_잠그고_남는_쪽_토큰만_발급한다() {
        givenDevice(9L, null);
        givenSocialUser(3L);

        LinkResponse response = authService.link(9L, REQUEST);

        assertThat(response.isNewUser()).isFalse();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("3");
        InOrder order = inOrder(userRepository, accountData, refreshTokenRepository);
        order.verify(userRepository).findByIdForUpdate(3L);
        order.verify(userRepository).findByIdForUpdate(9L);
        order.verify(accountData).merge(9L, 3L);
        order.verify(userRepository).delete(argThat(u -> u.getId() == 9L));
        order.verify(refreshTokenRepository).save(argThat(t -> t.getUserId() == 3L));
        verify(userRepository, times(1)).findByIdForUpdate(9L); // 옮기는 쪽을 먼저 따로 잠그지 않는다
        // 옮기는 쪽 refresh는 병합 처리기가 지운다. 남는 쪽의 다른 기기 refresh는 그대로 둔다
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void 병합은_두_사용자_행보다_옮기는_쪽의_다른_도메인_잠금을_먼저_잡는다() {
        givenDevice(9L, null);
        givenSocialUser(3L);

        authService.link(9L, REQUEST);

        InOrder order = inOrder(accountData, userRepository);
        order.verify(accountData).lockBeforeUsers(9L);
        order.verify(userRepository).findByIdForUpdate(3L);
        order.verify(userRepository).findByIdForUpdate(9L);
        order.verify(accountData).merge(9L, 3L);
    }

    @Test
    void 전환은_userId가_그대로라_다른_도메인_잠금을_따로_잡지_않는다() {
        givenDevice(1L, null);
        givenOwner(Optional.empty());

        authService.link(1L, REQUEST);

        verify(accountData, never()).lockBeforeUsers(anyLong());
    }

    @Test
    void 남는_쪽이_잠그기_전에_사라졌으면_전환한다() {
        // 조회와 잠금 사이에 그 소셜 계정이 탈퇴했다 — 소셜 ID가 비었으니 새 가입이다
        givenDevice(1L, null);
        givenOwner(Optional.of(2L));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        LinkResponse response = authService.link(1L, REQUEST);

        assertThat(response.isNewUser()).isTrue();
        assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("1");
        // 그룹 행 잠금은 병합 경로에 들어서며 먼저 잡는다 — 남는 쪽이 사라졌으면 병합만 하지 않는다
        verify(accountData, never()).merge(anyLong(), anyLong());
        verify(userRepository, never()).delete(any());
    }

    @Test
    void 병합된_쪽_사진은_커밋_뒤에_지우고_지우기에_실패해도_요청은_성공한다() {
        givenDevice(1L, IMAGE);
        givenSocialUser(2L);
        doThrow(new IllegalStateException("S3 down")).when(profileImageStorage).delete(IMAGE);

        LinkResponse response = authService.link(1L, REQUEST);

        assertThat(response.isNewUser()).isFalse();
        InOrder order = inOrder(transactionManager, profileImageStorage);
        order.verify(transactionManager).commit(any());
        order.verify(profileImageStorage).delete(IMAGE);
    }

    // 잠금은 호출마다 새 인스턴스 — 롤백된 트랜잭션의 변경(전환 시도)이 재시도에 새지 않는 실제 동작과 맞춘다
    private void givenDevice(long id, String image) {
        lenient().when(userRepository.findProviderById(id)).thenReturn(Optional.of(Provider.DEVICE));
        lenient().when(userRepository.findByIdForUpdate(id)).thenAnswer(invocation -> {
            User user = user(id, Provider.DEVICE);
            user.replaceProfileImage(image);
            return Optional.of(user);
        });
    }

    // 그 소셜 ID의 계정이 있고 잠글 수 있다
    private void givenSocialUser(long id) {
        givenOwner(Optional.of(id));
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(user(id, Provider.GOOGLE)));
    }

    private void givenOwner(Optional<Long> owner) {
        when(userRepository.findIdByProviderAndProviderUserId(Provider.GOOGLE, "g-sub"))
                .thenReturn(owner);
    }

    private static User user(long id, Provider provider) {
        User user = new User(provider, provider == Provider.DEVICE ? "device-" + id : "sub-" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
