package project.study.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.user.account.AccountData;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.image.ProfileImageStorage;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UsedSocialIdTokenRepository;
import project.study.user.repository.UserRepository;

/**
 * link의 DB 작업. 이 기기 비회원("옮기는 쪽")을 그 소셜 ID로 전환하거나, 그 소셜 ID의 기존 계정("남는 쪽")으로 병합한다. 트랜잭션과 토큰 발급은
 * 호출한 쪽({@link AuthService#link})이 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountLinker {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountData accountData;
    private final ProfileImageStorage profileImageStorage;
    private final UsedSocialIdTokenRepository usedSocialIdTokens;

    /** 토큰을 받을 사용자, 새로 전환됐는지, 커밋 뒤 지울 옮기는 쪽 사진 키(없으면 null). */
    record Linked(long userId, boolean isNewUser, String discardedImageKey) {}

    /** 같은 소셜 ID로 다른 기기가 먼저 전환해 unique 인덱스에서 졌다. 이 트랜잭션은 abort됐으니 새 트랜잭션에서 다시 해야 한다. */
    static final class ConversionRaceLostException extends RuntimeException {

        ConversionRaceLostException(DataIntegrityViolationException cause) {
            super("같은 소셜 계정으로 동시에 전환하다 졌습니다", cause);
        }
    }

    /**
     * 트랜잭션 밖의 미리 보기 — 병합 경로가 될지(옮기는 쪽이 비회원이고 그 소셜 ID의 다른 계정이 있다). 잠그지 않으므로 그 사이 바뀔 수
     * 있어 방 나가기를 정하는 데만 쓴다. 값만 읽고 엔티티는 싣지 않는다.
     */
    boolean mergeAhead(long userId, OAuthUserInfo social) {
        boolean device = userRepository
                .findProviderById(userId)
                .filter(provider -> provider == Provider.DEVICE)
                .isPresent();
        return device && ownerOf(social).filter(ownerId -> ownerId != userId).isPresent();
    }

    /**
     * idTokenHash는 검증한 ID 토큰의 SHA-256 hex다. 트랜잭션의 첫 문장으로 그 토큰을 사용 처리한다 — 이미 쓴 토큰이면 400이고, 이 트랜잭션이
     * 롤백되면(전환 경쟁) 사용 처리도 사라져 재시도가 같은 토큰을 쓸 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Linked link(long userId, OAuthUserInfo social, String idTokenHash) {
        if (!usedSocialIdTokens.markUsed(idTokenHash, social.expiresAt())) {
            throw new InvalidOAuthTokenException("이미 사용한 소셜 ID 토큰입니다");
        }
        // 잠그기 전에 소셜 ID의 주인부터 본다 — 병합이면 두 행을 ID 작은 쪽부터 잠가야 해서 옮기는 쪽을 먼저 잡을 수 없다
        Optional<Long> owner = ownerOf(social);
        if (owner.isEmpty()) {
            return convert(lockDevice(userId), social);
        }
        if (owner.get() == userId) {
            // 이 소셜 ID의 주인이 나다 — 이미 그 소셜 계정으로 로그인한 상태. 자기 자신과는 병합하지 않는다
            throw new AlreadyLinkedException();
        }
        return merge(userId, owner.get(), social);
    }

    // 남은 파일은 어디서도 참조하지 않으므로 지우지 못해도 요청은 성공으로 둔다 (ProfileImageService와 같은 방식)
    void deleteImageQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            profileImageStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("병합된 비회원의 프로필 사진 삭제 실패: key={}", key, e);
        }
    }

    // 같은 기기의 link가 겹치면 나중 쪽은 이 잠금을 기다린 뒤, 먼저 끝난 쪽이 전환했으면 409, 병합(행 삭제)했으면 404를 받는다
    private User lockDevice(long userId) {
        User user = userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"));
        if (user.getProvider() != Provider.DEVICE) {
            throw new AlreadyLinkedException();
        }
        return user;
    }

    private Linked convert(User source, OAuthUserInfo social) {
        source.linkSocialAccount(social.provider(), social.providerUserId(), social.email());
        try {
            // (provider, provider_user_id) unique 인덱스가 최종 판정이다 — 커밋 전에 여기서 부딪혀야 재시도로 돌릴 수 있다
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConversionRaceLostException(e);
        }
        // userId가 그대로라 비회원 시절 refresh도 살아 있다. 전환 시점의 비회원은 기기 하나이므로 전량 지우고 새 쌍을 준다
        refreshTokenRepository.deleteByUserId(source.getId());
        log.info("소셜 전환: userId={}, provider={}", source.getId(), social.provider());
        return new Linked(source.getId(), true, null);
    }

    private Linked merge(long sourceId, long targetId, OAuthUserInfo social) {
        // 옮기는 쪽이 얽힌 다른 도메인 행(그룹 행 등)을 users 행보다 먼저 잡는다 — 그 도메인이 잡는 순서와 같아야 교착하지 않는다
        accountData.lockBeforeUsers(sourceId);
        // 두 사용자 행을 ID 작은 쪽부터 잠근다 — 같은 두 행을 반대 순서로 잡는 요청과 교착하지 않게
        Optional<User> lower = userRepository.findByIdForUpdate(Math.min(sourceId, targetId));
        Optional<User> higher = userRepository.findByIdForUpdate(Math.max(sourceId, targetId));
        User source = (sourceId < targetId ? lower : higher)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"));
        if (source.getProvider() != Provider.DEVICE) {
            throw new AlreadyLinkedException();
        }
        if ((sourceId < targetId ? higher : lower).isEmpty()) {
            // 조회와 잠금 사이에 남는 쪽이 탈퇴했다 — 그 소셜 ID는 이제 비었으니 새 가입(전환)이다
            return convert(source, social);
        }
        accountData.merge(sourceId, targetId);
        String imageKey = source.getProfileImage();
        userRepository.delete(source);
        log.info("계정 병합: 비회원 {} → 소셜 {}", sourceId, targetId);
        return new Linked(targetId, false, imageKey);
    }

    private Optional<Long> ownerOf(OAuthUserInfo social) {
        return userRepository.findIdByProviderAndProviderUserId(social.provider(), social.providerUserId());
    }
}
