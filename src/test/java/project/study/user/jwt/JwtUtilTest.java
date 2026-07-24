package project.study.user.jwt;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
//
// import io.jsonwebtoken.Claims;
// import io.jsonwebtoken.Jwts;
// import io.jsonwebtoken.security.Keys;
// import io.jsonwebtoken.security.SignatureException;
// import java.nio.charset.StandardCharsets;
// import javax.crypto.SecretKey;
// import org.junit.jupiter.api.Test;
//
// class JwtUtilTest {
//
//     private static final String SECRET = "test-secret-key-that-is-at-least-32-chars-long";
//     private static final long ACCESS_EXPIRATION_MS = 3_600_000L;
//
//     private final JwtUtil jwtUtil = new JwtUtil(SECRET, ACCESS_EXPIRATION_MS);
//
//     @Test
//     void 액세스_토큰은_subject에_userId를_담는다() {
//         String token = jwtUtil.createAccessToken(1L);
//
//         assertThat(jwtUtil.getUserId(token)).isEqualTo("1");
//     }
//
//     @Test
//     void 액세스_토큰의_만료시간은_설정값을_따른다() {
//         String token = jwtUtil.createAccessToken(1L);
//
//         Claims claims = parse(token);
//         long lifetimeMs =
//                 claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
//
//         assertThat(lifetimeMs).isEqualTo(ACCESS_EXPIRATION_MS);
//     }
//
//     @Test
//     void 토큰은_설정된_시크릿으로_서명된다() {
//         String token = jwtUtil.createAccessToken(1L);
//
//         SecretKey otherKey =
//                 Keys.hmacShaKeyFor("another-secret-key-that-is-32-chars-or-more".getBytes(StandardCharsets.UTF_8));
//
//         assertThatThrownBy(() -> Jwts.parser().verifyWith(otherKey).build().parseSignedClaims(token))
//                 .isInstanceOf(SignatureException.class);
//     }
//
//     private Claims parse(String token) {
//         SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
//         return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
//     }
// }
