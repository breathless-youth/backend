package project.study.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.user.entity.RefreshToken;
import project.study.user.jwt.JwtUtil;
import project.study.user.repository.RefreshTokenRepository;

/** access(JWT)·refresh(opaque UUID) 쌍을 만든다. refresh는 SHA-256 해시로만 저장한다. 호출자는 유저 행 잠금 아래에서 부른다. */
@Component
class TokenIssuer {

    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshExpirationMs;

    TokenIssuer(
            JwtUtil jwtUtil,
            RefreshTokenRepository refreshTokenRepository,
            @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    AuthService.TokenPair issue(Long userId) {
        String accessToken = jwtUtil.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(
                new RefreshToken(userId, sha256(refreshToken), Instant.now().plusMillis(refreshExpirationMs)));
        return new AuthService.TokenPair(accessToken, refreshToken);
    }

    static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
