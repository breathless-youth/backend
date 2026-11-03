package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

class AppleTokenVerifierTest {

    private static final String BUNDLE_ID = "com.example.app";
    private static final String SERVICES_ID = "com.example.web";
    private static final String ISSUER = "https://appleid.apple.com";
    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String KID = "test-kid-1";

    private final KeyPair keyPair = generateRsaKeyPair();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final AppleTokenVerifier verifier = new AppleTokenVerifier(
            builder,
            new OAuthProperties(null, null, new OAuthProperties.Apple(BUNDLE_ID, SERVICES_ID, "", "", "", "", "")));

    @Test
    void 유효한_ID_토큰이면_유저정보를_반환한다() {
        String token =
                baseJwt("apple-user-123", BUNDLE_ID, ISSUER, futureDate()).compact();
        stubJwks();

        OAuthUserInfo userInfo = verifier.verify(token);

        assertThat(userInfo.provider()).isEqualTo(Provider.APPLE);
        assertThat(userInfo.providerUserId()).isEqualTo("apple-user-123");
        assertThat(userInfo.nonce()).isNull();
    }

    @Test
    void 토큰의_exp를_만료_시각으로_담는다() {
        Date exp = Date.from(Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS));
        String token = baseJwt("apple-user-123", BUNDLE_ID, ISSUER, exp).compact();
        stubJwks();

        assertThat(verifier.verify(token).expiresAt()).isEqualTo(exp.toInstant());
    }

    @Test
    void servicesId로_발급된_토큰도_통과한다() {
        String token =
                baseJwt("apple-user-123", SERVICES_ID, ISSUER, futureDate()).compact();
        stubJwks();

        assertThat(verifier.verify(token).providerUserId()).isEqualTo("apple-user-123");
    }

    @Test
    void 토큰의_nonce를_그대로_담는다() {
        String token = baseJwt("apple-user-123", BUNDLE_ID, ISSUER, futureDate())
                .claim("nonce", "abc-nonce")
                .compact();
        stubJwks();

        assertThat(verifier.verify(token).nonce()).isEqualTo("abc-nonce");
    }

    @Test
    void 허용_목록에_없는_aud는_거부한다() {
        String token =
                baseJwt("apple-user-123", "other-app", ISSUER, futureDate()).compact();
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void iss가_다른_토큰은_거부한다() {
        String token = baseJwt("apple-user-123", BUNDLE_ID, "https://evil.com", futureDate())
                .compact();
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 만료된_토큰은_거부한다() {
        String token = baseJwt("apple-user-123", BUNDLE_ID, ISSUER, pastDate()).compact();
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_거부한다() {
        KeyPair otherKeyPair = generateRsaKeyPair();
        String token = Jwts.builder()
                .header()
                .keyId(KID)
                .and()
                .subject("apple-user-123")
                .audience()
                .add(BUNDLE_ID)
                .and()
                .issuer(ISSUER)
                .expiration(futureDate())
                .issuedAt(new Date())
                .signWith(otherKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    private JwtBuilder baseJwt(String sub, String aud, String iss, Date exp) {
        return Jwts.builder()
                .header()
                .keyId(KID)
                .and()
                .subject(sub)
                .audience()
                .add(aud)
                .and()
                .issuer(iss)
                .expiration(exp)
                .issuedAt(new Date())
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256);
    }

    private void stubJwks() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        String n = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(pub.getModulus().toByteArray());
        String e = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(pub.getPublicExponent().toByteArray());
        String jwksJson =
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"%s\",\"n\":\"%s\",\"e\":\"%s\"}]}".formatted(KID, n, e);
        server.expect(requestTo(JWKS_URL)).andRespond(withSuccess(jwksJson, MediaType.APPLICATION_JSON));
    }

    private static Date futureDate() {
        return Date.from(Instant.now().plusSeconds(3600));
    }

    private static Date pastDate() {
        return Date.from(Instant.now().minusSeconds(3600));
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
