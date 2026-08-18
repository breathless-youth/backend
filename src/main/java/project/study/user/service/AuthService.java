package project.study.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.common.ConflictException;
import project.study.common.NotFoundException;
import project.study.studysession.repository.StudySessionRepository;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.RefreshToken;
import project.study.user.entity.User;
import project.study.user.entity.UserStatus;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthTokenVerifier;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;

@Slf4j
@Service
public class AuthService {

    private final List<OAuthTokenVerifier> tokenVerifiers;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StudySessionRepository studySessionRepository;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate transactionTemplate;
    private final long refreshExpirationMs;

    public AuthService(
            List<OAuthTokenVerifier> tokenVerifiers,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            StudySessionRepository studySessionRepository,
            JwtUtil jwtUtil,
            PlatformTransactionManager transactionManager,
            @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.tokenVerifiers = tokenVerifiers;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.studySessionRepository = studySessionRepository;
        this.jwtUtil = jwtUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.refreshExpirationMs = refreshExpirationMs;
    }

    // noRollbackFor: 재사용 감지 시 예외를 던져도 "전체 폐기"는 커밋되어야 한다
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(RefreshRequest request) {
        RefreshToken saved = refreshTokenRepository
                .findByTokenHash(sha256(request.refreshToken()))
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));

        if (saved.isExpired(Instant.now())) {
            refreshTokenRepository.delete(saved);
            throw new InvalidRefreshTokenException("만료된 refresh 토큰입니다");
        }
        // 조건부 UPDATE의 행 잠금이 동시 요청을 직렬화한다 — 정확히 한쪽만 사용 처리에 성공
        if (refreshTokenRepository.markUsedIfUnused(saved.getId(), Instant.now()) == 0) {
            // 이미 사용된 토큰의 재등장 = 재사용(탈취 의심) → 해당 유저 토큰 전체 폐기
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }

        User user = userRepository
                .findById(saved.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));
        rejectIfDeleted(user);

        TokenPair tokens = issueTokens(saved.getUserId());
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    public LoginResponse linkSocialAccount(Long userId, LinkSocialRequest request) {
        // 외부 HTTP 호출(소셜 검증)은 DB 트랜잭션 밖에서 수행한다
        OAuthUserInfo socialInfo = verifierFor(request.provider()).verify(request.idToken());
        try {
            return transactionTemplate.execute(status -> linkTransaction(userId, socialInfo));
        } catch (DataIntegrityViolationException e) {
            // 동시 전환 경쟁에서 패배 → 상대가 만든 소셜 유저가 이제 존재하므로 병합 경로로 재시도
            // (트랜잭션 안에서 catch하면 rollback-only 때문에 실패하므로 반드시 실행 단위를 분리)
            return transactionTemplate.execute(status -> linkTransaction(userId, socialInfo));
        }
    }

    private LoginResponse linkTransaction(Long userId, OAuthUserInfo socialInfo) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("존재하지 않는 사용자입니다"));
        if (user.getProvider() != Provider.DEVICE) {
            throw new ConflictException("이미 소셜 계정이 연동된 사용자입니다");
        }
        Optional<User> socialUser =
                userRepository.findByProviderAndProviderUserId(socialInfo.provider(), socialInfo.providerUserId());
        if (socialUser.isEmpty()) {
            // 전환: 식별자 쌍 교체 — userId 유지로 기록이 그대로 이어지고, 옛 deviceId 연결은 자동 해제된다
            user.linkSocialAccount(socialInfo.provider(), socialInfo.providerUserId(), socialInfo.email());
            userRepository.flush();
            TokenPair tokens = issueTokens(user.getId());
            // 전환 = 이 소셜 계정의 최초 가입 → isNewUser true (프론트가 프로필 설정 화면으로 보낸다)
            return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), true);
        }
        Long targetUserId = mergeInto(user, socialUser.get());
        TokenPair tokens = issueTokens(targetUserId);
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), false);
    }

    /**
     * 익명 유저의 기록을 기존 소셜 계정으로 병합하고 익명 유저를 소멸시킨다.
     * 기존 계정 세션과 기간이 겹치는 익명 세션은 폐기한다(기존 계정 기록 우선) —
     * 겹침을 남기면 이관 UPDATE가 무겹침 제약(ex_study_session_user_period)에 걸린다.
     */
    private Long mergeInto(User source, User target) {
        List<Long> overlapping = studySessionRepository.findIdsOverlapping(source.getId(), target.getId());
        if (!overlapping.isEmpty()) {
            // 엔티티 단위 삭제여야 status_event가 cascade로 함께 지워진다
            studySessionRepository.deleteAll(studySessionRepository.findAllById(overlapping));
            studySessionRepository.flush();
        }
        int moved = studySessionRepository.reassignUserId(source.getId(), target.getId());
        refreshTokenRepository.deleteByUserId(source.getId());
        userRepository.delete(source);
        log.info("계정 병합: 익명 {} → 소셜 {} (이관 {}건, 겹침 폐기 {}건)", source.getId(), target.getId(), moved, overlapping.size());
        return target.getId();
    }

    @Transactional
    public void logout(String refreshToken) {
        // DB에는 해시만 저장되므로 원문을 해시로 변환해서 삭제해야 한다
        refreshTokenRepository.deleteByTokenHash(sha256(refreshToken));
    }

    OAuthTokenVerifier verifierFor(Provider provider) {
        return tokenVerifiers.stream()
                .filter(verifier -> verifier.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new InvalidOAuthTokenException("지원하지 않는 프로바이더입니다: " + provider));
    }

    TokenPair issueTokens(Long userId) {
        String accessToken = jwtUtil.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(
                new RefreshToken(userId, sha256(refreshToken), Instant.now().plusMillis(refreshExpirationMs)));
        return new TokenPair(accessToken, refreshToken);
    }

    private static void rejectIfDeleted(User user) {
        if (user.getStatus() == UserStatus.DELETE) {
            throw new InvalidOAuthTokenException("삭제된 사용자입니다");
        }
    }

    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    record TokenPair(String accessToken, String refreshToken) {}
}
