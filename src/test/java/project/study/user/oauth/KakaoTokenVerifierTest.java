package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

class KakaoTokenVerifierTest {

    private static final String APP_KEY = "my-kakao-app-key";
    private static final String TOKEN_INFO_URL = "https://kauth.kakao.com/oauth/tokeninfo?id_token=id-token";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final KakaoTokenVerifier verifier = new KakaoTokenVerifier(builder, APP_KEY);

    @Test
    void 유효한_ID_토큰이면_유저정보를_반환한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-kakao-app-key\",\"sub\":\"kakao-12345\",\"email\":\"test@kakao.com\"}",
                        MediaType.APPLICATION_JSON));

        OAuthUserInfo userInfo = verifier.verify("id-token");

        assertThat(userInfo.provider()).isEqualTo(Provider.KAKAO);
        assertThat(userInfo.providerUserId()).isEqualTo("kakao-12345");
    }

    @Test
    void aud가_다른_토큰은_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(
                        withSuccess("{\"aud\":\"other-app-key\",\"sub\":\"kakao-12345\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 카카오가_거부한_토큰은_예외를_던진다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }
}
