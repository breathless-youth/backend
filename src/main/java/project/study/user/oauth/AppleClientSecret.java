package project.study.user.oauth;

import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.stereotype.Component;

/** 애플 REST API(토큰 교환·해지)에 쓰는 client_secret — ES256으로 서명한 5분짜리 JWT. 개인 키는 처음 쓸 때 읽는다. */
@Component
public class AppleClientSecret {

    private static final String AUDIENCE = "https://appleid.apple.com";
    private static final long VALID_SECONDS = 300;

    private final OAuthProperties.Apple apple;

    private volatile PrivateKey privateKey;

    public AppleClientSecret(OAuthProperties properties) {
        this.apple = properties.apple();
    }

    public String create(String clientId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header()
                .keyId(apple.keyId())
                .and()
                .issuer(apple.teamId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(VALID_SECONDS)))
                .audience()
                .add(AUDIENCE)
                .and()
                .subject(clientId)
                .signWith(privateKey(), Jwts.SIG.ES256)
                .compact();
    }

    private PrivateKey privateKey() {
        PrivateKey key = this.privateKey;
        if (key == null) {
            key = parse(apple.privateKey());
            this.privateKey = key;
        }
        return key;
    }

    private static PrivateKey parse(String pem) {
        try {
            String base64 = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("애플 개인 키를 읽을 수 없습니다", e);
        }
    }
}
