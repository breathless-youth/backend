package project.study.user.oauth;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import project.study.user.entity.Provider;

/**
 * 구글 tokeninfo로 ID 토큰을 검증한다. 구글이 토큰을 거부한 4xx는 400(토큰 문제), 5xx·429·연결·타임아웃·읽을 수 없는 응답은
 * 503(프로바이더 장애)이다. tokeninfo URL에 ID 토큰이 실리므로 예외 메시지·원인 예외를 로그나 밖으로 내보내지 않는다.
 */
@Slf4j
@Component
public class GoogleTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={idToken}";

    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;
    private final OAuthProperties.Google properties;

    public GoogleTokenVerifier(
            @Qualifier(SocialProviderHttpConfig.REST_CLIENT) RestClient restClient, OAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties.google();
    }

    @Override
    public Provider provider() {
        return Provider.GOOGLE;
    }

    @Override
    public OAuthUserInfo verify(String idToken) {
        GoogleTokenInfo tokenInfo;
        try {
            tokenInfo = restClient.get().uri(TOKEN_INFO_URL, idToken).retrieve().body(GoogleTokenInfo.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != TOO_MANY_REQUESTS) {
                throw new InvalidOAuthTokenException("구글 ID 토큰 검증에 실패했습니다");
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (tokenInfo == null
                || !StringUtils.hasText(tokenInfo.aud())
                || !properties.clientIds().contains(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("구글 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(
                Provider.GOOGLE, tokenInfo.sub(), tokenInfo.email(), tokenInfo.nonce(), tokenInfo.expiresAt());
    }

    private static SocialProviderUnavailableException unavailable(RestClientException e) {
        log.warn("구글 ID 토큰 검증 서버 호출 실패: {}", e.getClass().getSimpleName());
        return new SocialProviderUnavailableException("구글 ID 토큰 검증 서버에 연결할 수 없습니다");
    }

    /** exp는 epoch 초다(구글은 "1700000000"처럼 문자열로 준다). 없으면 null. */
    record GoogleTokenInfo(String aud, String sub, String email, String nonce, Long exp) {
        Instant expiresAt() {
            return exp == null ? null : Instant.ofEpochSecond(exp);
        }
    }
}
