package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppleClientSecretTest {

    private final KeyPair keyPair = generateEcKeyPair();

    @Test
    void 헤더와_클레임을_애플_규격으로_채워_서명한다() {
        AppleClientSecret secret = secretFor(pem(false));

        String jwt = secret.create("com.example.app");

        Jws<Claims> parsed =
                Jwts.parser().verifyWith(keyPair.getPublic()).build().parseSignedClaims(jwt);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEYID12345");
        Claims claims = parsed.getPayload();
        assertThat(claims.getIssuer()).isEqualTo("TEAMID1234");
        assertThat(claims.getSubject()).isEqualTo("com.example.app");
        assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
        long seconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(seconds).isEqualTo(300);
    }

    @Test
    void 개행이_리터럴_백슬래시_n으로_들어온_키도_읽는다() {
        AppleClientSecret secret = secretFor(pem(true));

        String jwt = secret.create("com.example.web");

        assertThat(Jwts.parser()
                        .verifyWith(keyPair.getPublic())
                        .build()
                        .parseSignedClaims(jwt)
                        .getPayload()
                        .getSubject())
                .isEqualTo("com.example.web");
    }

    private AppleClientSecret secretFor(String privateKey) {
        return new AppleClientSecret(new OAuthProperties(
                null,
                null,
                new OAuthProperties.Apple(List.of("b"), "s", "TEAMID1234", "KEYID12345", privateKey, "", "")));
    }

    private String pem(boolean escaped) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        return escaped ? pem.replace("\n", "\\n") : pem;
    }

    private static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
