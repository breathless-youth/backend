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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import project.study.user.entity.Provider;

/**
 * 애플 ID 토큰을 애플 공개키(JWKS)로 직접 검증한다. 서명·iss·exp·aud가 틀리면 400(토큰 문제), JWKS를 가져오지 못하면 503(프로바이더 장애)이다.
 */
@Slf4j
@Component
public class AppleTokenVerifier implements OAuthTokenVerifier {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String ISSUER = "https://appleid.apple.com";
    private static final long JWKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private final RestClient restClient;
    private final List<String> audiences;

    private volatile CachedJwks cachedJwks;

    public AppleTokenVerifier(
            @Qualifier(SocialProviderHttpConfig.REST_CLIENT) RestClient restClient, OAuthProperties properties) {
        this.restClient = restClient;
        // 비어 있으면 OAuthConfigCheck가 기동 뒤 로그·Sentry에 남긴다
        this.audiences = properties.apple().audiences();
    }

    @Override
    public Provider provider() {
        return Provider.APPLE;
    }

    @Override
    public OAuthUserInfo verify(String idToken) {
        // 받을 aud가 없으면 결과와 상관없이 거부되므로 JWKS도 부르지 않는다
        if (audiences.isEmpty()) {
            throw new InvalidOAuthTokenException("애플 로그인이 설정되지 않았습니다");
        }
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(new AppleKeyLocator())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

            if (claims.getAudience() == null || claims.getAudience().stream().noneMatch(audiences::contains)) {
                throw new InvalidOAuthTokenException("애플 ID 토큰의 대상(aud)이 일치하지 않습니다");
            }

            Instant expiresAt = claims.getExpiration() == null
                    ? null
                    : claims.getExpiration().toInstant();
            return new OAuthUserInfo(
                    Provider.APPLE,
                    claims.getSubject(),
                    (String) claims.get("email"),
                    (String) claims.get("nonce"),
                    expiresAt);
        } catch (InvalidOAuthTokenException | SocialProviderUnavailableException e) {
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
        Map<String, Object> response;
        try {
            response = restClient.get().uri(JWKS_URL).retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.warn("애플 JWKS 호출 실패: {}", e.getClass().getSimpleName());
            throw new SocialProviderUnavailableException("애플 공개키를 가져올 수 없습니다");
        }
        // 토큰이 아니라 애플 쪽 응답이 잘못됐다 — 잠시 뒤 다시 하면 된다
        if (response == null || !(response.get("keys") instanceof List)) {
            throw new SocialProviderUnavailableException("애플 공개키를 가져올 수 없습니다");
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
