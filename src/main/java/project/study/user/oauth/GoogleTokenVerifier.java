package project.study.user.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import project.study.user.entity.Provider;

@Component
public class GoogleTokenVerifier implements OAuthTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={idToken}";

    private final RestClient restClient;
    private final String clientId;

    public GoogleTokenVerifier(
            RestClient.Builder restClientBuilder, @Value("${oauth.google.client-id}") String clientId) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
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
        if (tokenInfo == null || !clientId.equals(tokenInfo.aud())) {
            throw new InvalidOAuthTokenException("구글 ID 토큰의 대상(aud)이 일치하지 않습니다");
        }
        return new OAuthUserInfo(Provider.GOOGLE, tokenInfo.sub());
    }

    record GoogleTokenInfo(String aud, String sub, String email) {}
}
