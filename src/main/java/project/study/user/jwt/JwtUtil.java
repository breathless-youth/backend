package project.study.user.jwt;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import io.jsonwebtoken.Claims;
// import io.jsonwebtoken.Jwts;
// import io.jsonwebtoken.security.Keys;
// import java.nio.charset.StandardCharsets;
// import java.time.Instant;
// import java.util.Date;
// import javax.crypto.SecretKey;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.stereotype.Component;
//
// @Component
// public class JwtUtil {
//
//     private final SecretKey secretKey;
//     private final long accessExpirationMs;
//
//     public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.access-expiration}") long accessExpirationMs)
// {
//         this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
//         this.accessExpirationMs = accessExpirationMs;
//     }
//
//     public String createAccessToken(Long userId) {
//         Instant now = Instant.now();
//         return Jwts.builder()
//                 .subject(String.valueOf(userId))
//                 .issuedAt(Date.from(now))
//                 .expiration(Date.from(now.plusMillis(accessExpirationMs)))
//                 .signWith(secretKey, Jwts.SIG.HS256)
//                 .compact();
//     }
//
//     public String getUserId(String token) {
//         return parseClaim(token).getSubject();
//     }
//
//     private Claims parseClaim(String token) {
//         return Jwts.parser()
//                 .verifyWith(secretKey)
//                 .build()
//                 .parseSignedClaims(token)
//                 .getPayload();
//     }
// }
