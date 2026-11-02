package project.study.user.oauth;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import project.study.user.dto.AppleClient;

/**
 * 탈퇴 시 애플 토큰 해지. 앱이 탈퇴 직전에 새로 받은 authorizationCode를 refresh token으로 바꾼 뒤 그 토큰을 해지한다. 어떤 실패도 밖으로
 * 던지지 않는다 — 애플 때문에 탈퇴가 실패하면 안 된다. 코드·client secret·애플 토큰은 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class AppleTokenRevoker {

    private static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    private static final String REVOKE_URL = "https://appleid.apple.com/auth/revoke";

    private final RestClient restClient;
    private final OAuthProperties.Apple apple;
    private final AppleClientSecret clientSecret;

    public AppleTokenRevoker(
            RestClient.Builder restClientBuilder, OAuthProperties properties, AppleClientSecret clientSecret) {
        this.restClient = restClientBuilder.build();
        this.apple = properties.apple();
        this.clientSecret = clientSecret;
    }

    public void revoke(String authorizationCode, AppleClient client) {
        if (!apple.revokeConfigured()) {
            log.warn("애플 해지 설정(teamId·keyId·privateKey)이 없어 토큰 해지를 건너뜁니다: client={}", client);
            return;
        }
        try {
            String clientId = client == AppleClient.ANDROID ? apple.servicesId() : apple.bundleId();
            String secret = clientSecret.create(clientId);

            MultiValueMap<String, String> exchange = new LinkedMultiValueMap<>();
            exchange.add("client_id", clientId);
            exchange.add("client_secret", secret);
            exchange.add("code", authorizationCode);
            exchange.add("grant_type", "authorization_code");
            if (client == AppleClient.ANDROID) {
                exchange.add("redirect_uri", apple.webRedirectUri());
            }
            Map<?, ?> tokens = restClient
                    .post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(exchange)
                    .retrieve()
                    .body(Map.class);
            Object refreshToken = tokens == null ? null : tokens.get("refresh_token");
            if (!(refreshToken instanceof String token) || !StringUtils.hasText(token)) {
                log.error("애플 토큰 해지 실패: 응답에 refresh_token이 없습니다. client={}", client);
                return;
            }

            MultiValueMap<String, String> revoke = new LinkedMultiValueMap<>();
            revoke.add("client_id", clientId);
            revoke.add("client_secret", secret);
            revoke.add("token", token);
            revoke.add("token_type_hint", "refresh_token");
            restClient
                    .post()
                    .uri(REVOKE_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(revoke)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error(
                    "애플 토큰 해지 실패: client={}, error={}: {}", client, e.getClass().getName(), e.getMessage());
        }
    }
}
