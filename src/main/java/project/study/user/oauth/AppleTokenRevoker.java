package project.study.user.oauth;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import project.study.user.dto.AppleClient;

/**
 * 탈퇴 시 애플 토큰 해지. 앱이 탈퇴 직전에 새로 받은 authorizationCode를 refresh token으로 바꾼 뒤 그 토큰을 해지한다. 어떤 실패도 밖으로
 * 던지지 않는다 — 애플 때문에 탈퇴가 실패하면 안 된다. 대신 실패·건너뜀은 모두 Sentry에 남긴다(sentry-logback이 없어 로그만으로는 아무도
 * 모른다). 코드·client secret·애플 토큰은 로그·Sentry에 남기지 않는다 — 고정 문구, 앱 종류, 예외 클래스·HTTP 상태만 싣는다.
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
            @Qualifier(SocialProviderHttpConfig.REST_CLIENT) RestClient restClient,
            OAuthProperties properties,
            AppleClientSecret clientSecret) {
        this.restClient = restClient;
        this.apple = properties.apple();
        this.clientSecret = clientSecret;
    }

    public void revoke(String authorizationCode, AppleClient client) {
        if (!apple.revokeConfigured()) {
            report(SentryLevel.WARNING, "애플 토큰 해지를 건너뜀: 해지 설정(teamId·keyId·privateKey)이 없다", client, null);
            return;
        }
        if (client == null) {
            // 코드를 만든 클라이언트(번들 ID·Services ID)로만 교환할 수 있다 — 짐작해 고르면 다른 쪽 코드는 반드시 실패한다
            report(SentryLevel.ERROR, "애플 토큰 해지 실패: 앱 종류(appleClient)가 없다", null, null);
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
                report(SentryLevel.ERROR, "애플 토큰 해지 실패: 교환 응답에 refresh_token이 없다", client, null);
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
            report(SentryLevel.ERROR, "애플 토큰 해지 실패: 애플 호출 실패", client, e);
        }
    }

    // 예외 메시지는 싣지 않는다 — 클래스와 HTTP 상태면 원인을 가를 수 있다
    private static void report(SentryLevel level, String what, AppleClient client, Exception e) {
        String detail = e == null ? "" : ", error=" + e.getClass().getName() + httpStatus(e);
        String message = what + " (client=" + client + detail + ")";
        if (level == SentryLevel.WARNING) {
            log.warn(message);
        } else {
            log.error(message);
        }
        Sentry.captureMessage(message, level);
    }

    private static String httpStatus(Exception e) {
        return e instanceof RestClientResponseException response
                ? ", status=" + response.getStatusCode().value()
                : "";
    }
}
