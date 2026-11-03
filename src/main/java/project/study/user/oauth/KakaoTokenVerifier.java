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
 * 카카오 tokeninfo로 ID 토큰을 검증한다. 카카오가 토큰을 거부한 4xx는 400(토큰 문제), 5xx·429·연결·타임아웃·읽을 수 없는 응답은
 * 503(프로바이더 장애)이다. tokeninfo URL에 ID 토큰이 실리므로 예외 메시지·원인 예외를 로그나 밖으로 내보내지 않는다.
 */
@Slf4j
@Component
public class KakaoTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://kauth.kakao.com/oauth/tokeninfo?id_token={idToken}";

    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;
    private final OAuthProperties.Kakao properties;

    public KakaoTokenVerifier(
            @Qualifier(SocialProviderHttpConfig.REST_CLIENT) RestClient restClient, OAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties.kakao();
        if (this.properties.appKeys().isEmpty()) {
            log.warn("oauth.kakao.app-keys(OAUTH_KAKAO_APP_KEYS)가 비어 있어 카카오 로그인을 모두 거부한다");
        }
    }

    @Override
    public Provider provider() {
        return Provider.KAKAO;
    }

    @Override
    public OAuthUserInfo verify(String idToken) {
        // 받을 aud가 없으면 결과와 상관없이 거부되므로 토큰을 카카오에 보내지도 않는다
        if (properties.appKeys().isEmpty()) {
            throw new InvalidOAuthTokenException("카카오 로그인이 설정되지 않았습니다");
        }
        KakaoTokenInfo tokenInfo;
        try {
            tokenInfo = restClient.get().uri(TOKEN_INFO_URL, idToken).retrieve().body(KakaoTokenInfo.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != TOO_MANY_REQUESTS) {
                throw new InvalidOAuthTokenException("카카오 ID 토큰 검증에 실패했습니다");
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (tokenInfo == null
                || !StringUtils.hasText(tokenInfo.aud())
                || !properties.appKeys().contains(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("카카오 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(
                Provider.KAKAO, tokenInfo.sub(), tokenInfo.email(), tokenInfo.nonce(), tokenInfo.expiresAt());
    }

    private static SocialProviderUnavailableException unavailable(RestClientException e) {
        log.warn("카카오 ID 토큰 검증 서버 호출 실패: {}", e.getClass().getSimpleName());
        return new SocialProviderUnavailableException("카카오 ID 토큰 검증 서버에 연결할 수 없습니다");
    }

    /** exp는 epoch 초다. 없으면 null. */
    record KakaoTokenInfo(String aud, String sub, String email, String nonce, Long exp) {
        Instant expiresAt() {
            return exp == null ? null : Instant.ofEpochSecond(exp);
        }
    }
}
