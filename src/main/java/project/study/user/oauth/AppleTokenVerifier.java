package project.study.user.oauth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

@Component
public class AppleTokenVerifier implements OAuthTokenVerifier {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String ISSUER = "https://appleid.apple.com";
    private static final long JWKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private final RestClient restClient;
    private final String clientId;

    private volatile CachedJwks cachedJwks;

    public AppleTokenVerifier(
            RestClient.Builder restClientBuilder, @Value("${oauth.apple.client-id}") String clientId) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
    }

    @Override
    public Provider provider() {
        return Provider.APPLE;
    }

    @Override
    public OAuthUserInfo verify(String idToken) {
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(new AppleKeyLocator())
                    .requireIssuer(ISSUER)
                    .requireAudience(clientId)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

            return new OAuthUserInfo(Provider.APPLE, claims.getSubject(), (String) claims.get("email"));
        } catch (InvalidOAuthTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidOAuthTokenException("애플 ID 토큰 검증에 실패했습니다");
        }
    }

    @SuppressWarnings("unchecked")
    private List<JwkKey> getJwks() {
        CachedJwks cached = this.cachedJwks;
        if (cached != null && !cached.isExpired()) {
            return cached.keys();
        }
        Map<String, Object> response = restClient.get().uri(JWKS_URL).retrieve().body(Map.class);
        if (response == null || !response.containsKey("keys")) {
            throw new InvalidOAuthTokenException("애플 JWKS를 가져올 수 없습니다");
        }
        List<Map<String, String>> rawKeys = (List<Map<String, String>>) response.get("keys");
        List<JwkKey> keys = rawKeys.stream()
                .map(k -> new JwkKey(k.get("kid"), k.get("n"), k.get("e")))
                .toList();
        this.cachedJwks = new CachedJwks(keys, Instant.now().plusMillis(JWKS_CACHE_TTL_MS));
        return keys;
    }

    private static PublicKey toRsaPublicKey(JwkKey jwk) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new InvalidOAuthTokenException("애플 공개키 변환에 실패했습니다");
        }
    }

    private class AppleKeyLocator implements Locator<Key> {
        @Override
        public Key locate(Header header) {
            String kid = (String) header.get("kid");
            if (kid == null) {
                throw new InvalidOAuthTokenException("애플 ID 토큰에 kid가 없습니다");
            }
            JwkKey key = findKey(kid, getJwks());
            if (key == null) {
                cachedJwks = null;
                key = findKey(kid, getJwks());
            }
            if (key == null) {
                throw new InvalidOAuthTokenException("애플 JWKS에서 일치하는 키를 찾을 수 없습니다");
            }
            return toRsaPublicKey(key);
        }

        private static JwkKey findKey(String kid, List<JwkKey> keys) {
            return keys.stream().filter(k -> kid.equals(k.kid())).findFirst().orElse(null);
        }
    }

    record JwkKey(String kid, String n, String e) {}

    record CachedJwks(List<JwkKey> keys, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
