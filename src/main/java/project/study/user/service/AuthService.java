package project.study.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.user.dto.LoginRequest;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.RefreshToken;
import project.study.user.entity.User;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthTokenVerifier;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;

@Service
public class AuthService {

    private final List<OAuthTokenVerifier> tokenVerifiers;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate transactionTemplate;
    private final long refreshExpirationMs;

    public AuthService(
            List<OAuthTokenVerifier> tokenVerifiers,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtUtil jwtUtil,
            PlatformTransactionManager transactionManager,
            @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.tokenVerifiers = tokenVerifiers;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtUtil = jwtUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public LoginResponse login(LoginRequest request) {
        // 외부 HTTP 호출(구글 검증)은 DB 트랜잭션 밖에서 수행한다
        OAuthUserInfo userInfo = verifierFor(request.provider()).verify(request.idToken());
        try {
            return transactionTemplate.execute(status -> loginTransaction(userInfo));
        } catch (DataIntegrityViolationException e) {
            // 동일 유저의 동시 첫 로그인 경쟁에서 패배 → 상대가 만든 유저를 새 트랜잭션에서 조회해 재시도
            // (트랜잭션 안에서 catch하면 rollback-only 때문에 실패하므로 반드시 실행 단위를 분리)
            return transactionTemplate.execute(status -> loginTransaction(userInfo));
        }
    }

    private LoginResponse loginTransaction(OAuthUserInfo userInfo) {
        Optional<User> existing =
                userRepository.findByProviderAndProviderUserId(userInfo.provider(), userInfo.providerUserId());
        boolean isNewUser = existing.isEmpty();
        User user =
                existing.orElseGet(() -> userRepository.save(new User(userInfo.provider(), userInfo.providerUserId())));

        TokenPair tokens = issueTokens(user.getId());
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), isNewUser);
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

        TokenPair tokens = issueTokens(saved.getUserId());
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    @Transactional
    public void logout(String refreshToken) {
        // DB에는 해시만 저장되므로 원문을 해시로 변환해서 삭제해야 한다
        refreshTokenRepository.deleteByTokenHash(sha256(refreshToken));
    }

    private OAuthTokenVerifier verifierFor(Provider provider) {
        return tokenVerifiers.stream()
                .filter(verifier -> verifier.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new InvalidOAuthTokenException("지원하지 않는 프로바이더입니다: " + provider));
    }

    private TokenPair issueTokens(Long userId) {
        String accessToken = jwtUtil.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(
                new RefreshToken(userId, sha256(refreshToken), Instant.now().plusMillis(refreshExpirationMs)));
        return new TokenPair(accessToken, refreshToken);
    }

    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    private record TokenPair(String accessToken, String refreshToken) {}
}
