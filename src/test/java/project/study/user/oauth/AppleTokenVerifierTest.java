package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

class AppleTokenVerifierTest {

    private static final String CLIENT_ID = "com.example.app";
    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String KID = "test-kid-1";

    private final KeyPair keyPair = generateRsaKeyPair();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final AppleTokenVerifier verifier = new AppleTokenVerifier(builder, CLIENT_ID);

    @Test
    void 유효한_ID_토큰이면_유저정보를_반환한다() {
        String token = buildAppleJwt("apple-user-123", CLIENT_ID, "https://appleid.apple.com", futureDate());
        stubJwks();

        OAuthUserInfo userInfo = verifier.verify(token);

        assertThat(userInfo.provider()).isEqualTo(Provider.APPLE);
        assertThat(userInfo.providerUserId()).isEqualTo("apple-user-123");
    }

    @Test
    void aud가_다른_토큰은_거부한다() {
        String token = buildAppleJwt("apple-user-123", "other-app", "https://appleid.apple.com", futureDate());
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void iss가_다른_토큰은_거부한다() {
        String token = buildAppleJwt("apple-user-123", CLIENT_ID, "https://evil.com", futureDate());
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 만료된_토큰은_거부한다() {
        String token = buildAppleJwt("apple-user-123", CLIENT_ID, "https://appleid.apple.com", pastDate());
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
                .add(CLIENT_ID)
                .and()
                .issuer("https://appleid.apple.com")
                .expiration(futureDate())
                .issuedAt(new Date())
                .signWith(otherKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
        stubJwks();

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidOAuthTokenException.class);
    }

    private String buildAppleJwt(String sub, String aud, String iss, Date exp) {
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
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
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
