package project.study.user.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import project.study.user.entity.Provider;

@Component
public class KakaoTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://kauth.kakao.com/oauth/tokeninfo?id_token={idToken}";

    private final RestClient restClient;
    private final String appKey;

    public KakaoTokenVerifier(RestClient.Builder restClientBuilder, @Value("${oauth.kakao.app-key}") String appKey) {
        this.restClient = restClientBuilder.build();
        this.appKey = appKey;
    }

    @Override
    public Provider provider() {
        return Provider.KAKAO;
    }

    @Override
    public OAuthUserInfo verify(String idToken) {
        KakaoTokenInfo tokenInfo;
        try {
            tokenInfo = restClient.get().uri(TOKEN_INFO_URL, idToken).retrieve().body(KakaoTokenInfo.class);
        } catch (RestClientResponseException e) {
            throw new InvalidOAuthTokenException("카카오 ID 토큰 검증에 실패했습니다");
        }
        if (tokenInfo == null || !appKey.equals(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("카카오 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(Provider.KAKAO, tokenInfo.sub());
    }

    record KakaoTokenInfo(String aud, String sub) {}
}
