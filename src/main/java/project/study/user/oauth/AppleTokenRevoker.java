package project.study.user.oauth;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
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
    private static final int BAD_REQUEST = 400;

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
        List<String> clientIds = clientIdsFor(client);
        if (clientIds.isEmpty()) {
            report(SentryLevel.ERROR, "애플 토큰 해지 실패: 이 앱 종류의 클라이언트 ID 설정이 없다", client, null);
            return;
        }
        try {
            Exchanged exchanged = exchangeTryingEach(clientIds, authorizationCode, client);
            String token = refreshTokenOf(exchanged.tokens());
            if (token == null) {
                report(SentryLevel.ERROR, "애플 토큰 해지 실패: 교환 응답에 refresh_token이 없다", client, null);
                return;
            }
            revokeToken(exchanged.clientId(), exchanged.secret(), token);
        } catch (Exception e) {
            report(SentryLevel.ERROR, "애플 토큰 해지 실패: 애플 호출 실패", client, e);
        }
    }

    private List<String> clientIdsFor(AppleClient client) {
        return client == AppleClient.ANDROID
                ? Stream.of(apple.servicesId()).filter(StringUtils::hasText).toList()
                : apple.bundleIds();
    }

    private record Exchanged(String clientId, String secret, Map<?, ?> tokens) {}

    /**
     * 번들 ID가 여럿이면(한 서버를 쓰는 앱 변형) 요청이 어느 앱의 코드인지 알려 주지 않아 차례로 교환해 본다. 다른 앱의 코드는 400이라 그때만
     * 다음으로 넘어가고, 5xx·연결 실패나 마지막 시도의 400은 그대로 던진다. 설정이 하나뿐인 운영은 한 번만 부른다.
     */
    private Exchanged exchangeTryingEach(List<String> clientIds, String authorizationCode, AppleClient client) {
        for (int i = 0; ; i++) {
            String clientId = clientIds.get(i);
            String secret = clientSecret.create(clientId);
            try {
                return new Exchanged(clientId, secret, exchange(clientId, secret, authorizationCode, client));
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != BAD_REQUEST || i + 1 == clientIds.size()) {
                    throw e;
                }
            }
        }
    }

    private static String refreshTokenOf(Map<?, ?> tokens) {
        Object refreshToken = tokens == null ? null : tokens.get("refresh_token");
        return refreshToken instanceof String token && StringUtils.hasText(token) ? token : null;
    }

    private Map<?, ?> exchange(String clientId, String secret, String authorizationCode, AppleClient client) {
        MultiValueMap<String, String> exchange = new LinkedMultiValueMap<>();
        exchange.add("client_id", clientId);
        exchange.add("client_secret", secret);
        exchange.add("code", authorizationCode);
        exchange.add("grant_type", "authorization_code");
        if (client == AppleClient.ANDROID) {
            exchange.add("redirect_uri", apple.webRedirectUri());
        }
        return restClient
                .post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(exchange)
                .retrieve()
                .body(Map.class);
    }

    private void revokeToken(String clientId, String secret, String token) {
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
