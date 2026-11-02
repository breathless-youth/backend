package project.study.user.oauth;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import project.study.user.entity.Provider;

@Component
public class GoogleTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={idToken}";

    private final RestClient restClient;
    private final OAuthProperties.Google properties;

    public GoogleTokenVerifier(RestClient.Builder restClientBuilder, OAuthProperties properties) {
        this.restClient = restClientBuilder.build();
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
        } catch (RestClientResponseException e) {
            throw new InvalidOAuthTokenException("구글 ID 토큰 검증에 실패했습니다");
        }
        if (tokenInfo == null || !properties.clientIds().contains(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("구글 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(Provider.GOOGLE, tokenInfo.sub(), tokenInfo.email(), tokenInfo.nonce());
    }

    record GoogleTokenInfo(String aud, String sub, String email, String nonce) {}
}
