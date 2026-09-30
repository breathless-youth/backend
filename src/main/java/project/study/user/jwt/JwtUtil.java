package project.study.user.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** access 토큰(JWT, HS256) 발급·검증. 클레임은 subject=userId뿐이다 — 개인정보는 싣지 않는다. */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessExpirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.access-expiration}") long accessExpirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
    }

    public String createAccessToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessExpirationMs)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /** 서명·만료를 검증하고 subject를 돌려준다. 실패하면 {@link io.jsonwebtoken.JwtException}. */
    public String getUserId(String token) {
        return parseClaim(token).getSubject();
    }

    private Claims parseClaim(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
