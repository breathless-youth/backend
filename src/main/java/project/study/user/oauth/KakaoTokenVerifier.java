package project.study.user.oauth;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import project.study.user.entity.Provider;

@Component
public class KakaoTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://kauth.kakao.com/oauth/tokeninfo?id_token={idToken}";

    private final RestClient restClient;
    private final OAuthProperties.Kakao properties;

    public KakaoTokenVerifier(RestClient.Builder restClientBuilder, OAuthProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties.kakao();
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
        } catch (RestClientException e) {
            throw new InvalidOAuthTokenException("카카오 ID 토큰 검증에 실패했습니다");
        }
        if (tokenInfo == null
                || !StringUtils.hasText(tokenInfo.aud())
                || !properties.appKeys().contains(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("카카오 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(Provider.KAKAO, tokenInfo.sub(), tokenInfo.email(), tokenInfo.nonce());
    }

    record KakaoTokenInfo(String aud, String sub, String email, String nonce) {}
}
