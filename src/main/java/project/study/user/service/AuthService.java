package project.study.user.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.room.service.CurrentRoomLeaver;
import project.study.user.dto.LinkResponse;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.RefreshToken;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.oauth.SocialNonce;
import project.study.user.oauth.SocialTokenVerifiers;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;
import project.study.user.service.AccountLinker.ConversionRaceLostException;
import project.study.user.service.AccountLinker.Linked;

/**
 * 비회원(DEVICE) 토큰 발급, refresh 회전, 소셜 로그인(link).
 *
 * <p>refresh는 opaque UUID를 SHA-256 해시로만 저장하고, 회전 시 삭제 대신 {@code usedAt}을 마킹한다.
 * 사용된 토큰이 다시 오면 탈취 의심으로 그 유저의 refresh를 전량 폐기한다 (ADR-0002).
 *
 * <p>발급·회전·전량 폐기·병합은 모두 유저 행 잠금({@code SELECT ... FOR UPDATE}) 아래에서 실행한다. 잠금이 없으면
 * 재사용 감지의 전량 폐기가 동시에 커밋 중인 회전의 새 토큰을 삭제 대상 조회에서 놓쳐 살려 둔다.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenIssuer tokenIssuer;
    private final SocialTokenVerifiers socialTokenVerifiers;
    private final CurrentRoomLeaver currentRoomLeaver;
    private final TransactionTemplate linkTransaction;
    private final AccountLinker accountLinker;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenIssuer tokenIssuer,
            SocialTokenVerifiers socialTokenVerifiers,
            CurrentRoomLeaver currentRoomLeaver,
            PlatformTransactionManager transactionManager,
            AccountLinker accountLinker) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenIssuer = tokenIssuer;
        this.socialTokenVerifiers = socialTokenVerifiers;
        this.currentRoomLeaver = currentRoomLeaver;
        this.accountLinker = accountLinker;
        // link 전용 — 누가 트랜잭션 안에서 불러도 전환 경쟁의 재시도가 abort된 트랜잭션에 섞이지 않고 늘 새 트랜잭션이 되게 한다
        this.linkTransaction = new TransactionTemplate(transactionManager);
        this.linkTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 기기 등록(재등록)용 발급 — 잠금 아래에서 이 행이 아직 그 기기의 비회원이면, refresh를 전량 폐기한 뒤 새 쌍을 만든다.
     *
     * <p>등록과 발급은 트랜잭션이 따로라, 그 사이 link가 이 비회원을 소셜 계정으로 전환(행 유지)하거나 병합(행 삭제)할 수 있다. 그러면
     * 빈 값이다 — deviceId만으로 소셜 계정의 토큰을 받으면 안 되고, 전환이 막 발급한 refresh를 지워서도 안 된다. 호출한 쪽이 다시
     * 등록한다(그 deviceId는 이제 어느 비회원과도 맞지 않아 새 비회원이 생긴다).
     *
     * <p>DEVICE 유저는 기기 하나라는 전제라, 재등록은 곧 "다른 곳에 남은 refresh를 무효화"하는 동작이다.
     * 재사용 감지용 tombstone까지 지워지지만, 남는 토큰이 하나도 없으므로 옛 토큰이 다시 와도
     * "알 수 없는 토큰"으로 거부되어 문제없다. 회전({@link #refresh})은 tombstone을 남겨야 하므로 이 경로를 쓰지 않는다.
     */
    @Transactional
    public Optional<TokenPair> issueDeviceTokens(Long userId, String deviceId) {
        if (userRepository
                .findByIdAndIdentityForUpdate(userId, Provider.DEVICE, deviceId)
                .isEmpty()) {
            return Optional.empty();
        }
        refreshTokenRepository.deleteByUserId(userId);
        return Optional.of(tokenIssuer.issue(userId));
    }

    // noRollbackFor: 재사용 감지 시 예외를 던져도 "전체 폐기"는 커밋되어야 한다
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(RefreshRequest request) {
        String tokenHash = TokenIssuer.sha256(request.refreshToken());
        Long userId = refreshTokenRepository
                .findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));
        // 유저 행 잠금 = 이 유저의 발급·회전·폐기 뮤텍스. 유저가 없으면(삭제) refresh가 살아 있어도 거부한다
        userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));
        // 토큰 행은 잠금을 잡은 뒤에 읽는다 — 잠금을 기다리는 사이 다른 요청이 회전시켰다면(usedAt 마킹)
        // 그 상태를 봐야 아래 재사용 검사가 tombstone 삭제가 아니라 전량 폐기로 간다 (Codex 2차 P2)
        RefreshToken saved = refreshTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));

        // 재사용 검사가 만료 검사보다 먼저다: 만료를 먼저 보면 탈취자가 회전시킨 토큰이 만료된 뒤
        // 피해자가 재시도할 때 행만 삭제되고 끝나 전량 폐기가 안 일어난다(재사용 감지 우회)
        if (saved.getUsedAt() != null) {
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }
        if (saved.isExpired(Instant.now())) {
            refreshTokenRepository.delete(saved);
            throw new InvalidRefreshTokenException("만료된 refresh 토큰입니다");
        }
        // 조건부 UPDATE의 행 잠금이 동시 요청을 직렬화한다 — 정확히 한쪽만 사용 처리에 성공
        // (위 usedAt 검사는 조회 시점 스냅샷이라 동시 요청 레이스는 여기서만 걸러진다)
        if (refreshTokenRepository.markUsedIfUnused(saved.getId(), Instant.now()) == 0) {
            // 이미 사용된 토큰의 재등장 = 재사용(탈취 의심) → 해당 유저 토큰 전체 폐기
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }

        TokenPair tokens = tokenIssuer.issue(saved.getUserId());
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 이 기기의 refresh만 폐기한다. 유저 행 잠금 아래에서 회전과 같은 뮤텍스를 쓴다.
     *
     * <p>호출자 소유의 미사용 토큰을 한 문장으로 지우면 끝이다. 0이면 토큰을 조회해, 내 것인데 이미 회전된 토큰이면 재사용 증거를 지우려는
     * 시도로 보고 전량 폐기한다(재사용 감지와 같다). 없거나 남의 토큰이면 아무것도 하지 않는다 — 소유 검증 없이 지우면 인증만 통과한
     * 쪽이 남의 토큰으로 그 사람을 로그아웃시킬 수 있다. 호출자 행이 없어도 조용히 끝난다.
     */
    @Transactional
    public void logout(Long userId, String refreshToken) {
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return;
        }
        String tokenHash = TokenIssuer.sha256(refreshToken);
        if (refreshTokenRepository.deleteByTokenHashAndUserIdIfUnused(tokenHash, userId) == 1) {
            return;
        }
        refreshTokenRepository
                .findByTokenHash(tokenHash)
                .filter(saved -> saved.getUserId().equals(userId))
                .ifPresent(saved -> refreshTokenRepository.deleteByUserId(userId));
    }

    /**
     * 비회원을 소셜 계정으로 로그인시킨다. 처음 쓰는 소셜 ID면 이 사용자를 그대로 전환하고, 이미 있으면 이 기기의 데이터를 그 계정으로 합친다.
     *
     * <p>트랜잭션이 아니다. 프로바이더 검증(HTTP)과 방 나가기(자체 트랜잭션·브로드캐스트)는 DB 트랜잭션 밖에서 먼저 하고, 잠금·전환·병합·발급만
     * 한 트랜잭션으로 묶는다. 병합으로 버려진 프로필 사진 파일은 커밋 뒤에 지운다.
     *
     * <p>ID 토큰은 한 번만 받는다 — 같은 토큰이 다시 오면 400 {@code INVALID_SOCIAL_TOKEN}이다({@link AccountLinker#link}).
     *
     * <p>방 나가기는 병합 규칙이라 병합이 될 때만 한다. 전환은 userId가 그대로라 참가가 그대로 맞고, 409·404는 부작용이 없어야 한다.
     * 미리 보기와 실제가 엇갈려 방에 남은 채 병합되면 병합 처리기가 그 참가를 닫는다(MEMBER_LEFT 없이).
     */
    public LinkResponse link(Long userId, LinkSocialRequest request) {
        OAuthUserInfo social = socialTokenVerifiers.verify(request.provider(), request.idToken());
        SocialNonce.check(social, request.nonce());
        String idTokenHash = TokenIssuer.sha256(signedPart(request.idToken()));
        if (accountLinker.mergeAhead(userId, social)) {
            currentRoomLeaver.leave(userId);
        }
        LinkOutcome outcome;
        try {
            outcome = linkInNewTransaction(userId, social, idTokenHash);
        } catch (ConversionRaceLostException e) {
            // 다른 기기가 같은 소셜 ID로 먼저 전환했다 — 그 사용자가 이제 있으니 새 트랜잭션에서 병합 경로로 한 번 더 한다
            // (제약 위반으로 abort된 트랜잭션 안에서는 다시 할 수 없다). 이번엔 병합이라 방에서 먼저 뺀다. 또 지면 그대로 실패시킨다
            currentRoomLeaver.leave(userId);
            outcome = linkInNewTransaction(userId, social, idTokenHash);
        }
        accountLinker.deleteImageQuietly(outcome.linked().discardedImageKey());
        TokenPair tokens = outcome.tokens();
        return new LinkResponse(
                tokens.accessToken(), tokens.refreshToken(), outcome.linked().isNewUser());
    }

    // 전환·병합과 발급을 한 트랜잭션으로 — 발급은 잠금 아래에서, 남는 쪽(병합) 또는 전환된 사용자에게 한다
    private LinkOutcome linkInNewTransaction(Long userId, OAuthUserInfo social, String idTokenHash) {
        return linkTransaction.execute(status -> {
            Linked linked = accountLinker.link(userId, social, idTokenHash);
            return new LinkOutcome(linked, tokenIssuer.issue(linked.userId()));
        });
    }

    private record LinkOutcome(Linked linked, TokenPair tokens) {}

    /**
     * 사용 기록의 키로 쓸 부분: 서명이 덮는 {@code header.payload}. 서명 조각은 같은 바이트를 여러 문자열로 쓸 수 있어(base64url의 남는
     * 비트·패딩) 토큰 전체를 키로 하면 서명만 고쳐 쓴 토큰이 새 토큰으로 통과한다. 앞 두 조각은 한 글자만 바뀌어도 서명 검증이 깨진다.
     */
    static String signedPart(String idToken) {
        int lastDot = idToken.lastIndexOf('.');
        return lastDot < 0 ? idToken : idToken.substring(0, lastDot);
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
