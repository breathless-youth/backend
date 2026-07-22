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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final long refreshExpirationMs;

    public AuthService(
            List<OAuthTokenVerifier> tokenVerifiers,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtUtil jwtUtil,
            @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.tokenVerifiers = tokenVerifiers;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtUtil = jwtUtil;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        OAuthUserInfo userInfo = verifierFor(request.provider()).verify(request.idToken());

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

        if (saved.isUsed()) {
            // 회전된 토큰의 재사용 = 탈취 의심 → 해당 유저 토큰 전체 폐기
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }
        if (saved.isExpired(Instant.now())) {
            refreshTokenRepository.delete(saved);
            throw new InvalidRefreshTokenException("만료된 refresh 토큰입니다");
        }

        saved.markUsed();
        TokenPair tokens = issueTokens(saved.getUserId());
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
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
