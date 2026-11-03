package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

class KakaoTokenVerifierTest {

    private static final String TOKEN_INFO_URL = "https://kauth.kakao.com/oauth/tokeninfo?id_token=id-token";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final KakaoTokenVerifier verifier = new KakaoTokenVerifier(
            builder.build(),
            new OAuthProperties(
                    null, new OAuthProperties.Kakao(List.of("my-kakao-app-key", "second-kakao-app-key")), null));

    @Test
    void 유효한_ID_토큰이면_유저정보를_반환한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-kakao-app-key\",\"sub\":\"kakao-12345\",\"email\":\"test@kakao.com\"}",
                        MediaType.APPLICATION_JSON));

        OAuthUserInfo userInfo = verifier.verify("id-token");

        assertThat(userInfo.provider()).isEqualTo(Provider.KAKAO);
        assertThat(userInfo.providerUserId()).isEqualTo("kakao-12345");
        assertThat(userInfo.nonce()).isNull();
    }

    @Test
    void 숫자로_온_exp를_만료_시각으로_담는다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-kakao-app-key\",\"sub\":\"kakao-12345\",\"exp\":1700000000}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").expiresAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void 목록의_두번째_aud로_발급된_토큰도_통과한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"second-kakao-app-key\",\"sub\":\"kakao-12345\"}", MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").providerUserId()).isEqualTo("kakao-12345");
    }

    @Test
    void 토큰의_nonce를_그대로_담는다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-kakao-app-key\",\"sub\":\"kakao-12345\",\"nonce\":\"abc-nonce\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").nonce()).isEqualTo("abc-nonce");
    }

    @Test
    void 목록에_없는_aud는_거부한다() {
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

    @Test
    void 네트워크_실패는_토큰_문제가_아니라_프로바이더_장애다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> verifier.verify("id-token"))
                .isInstanceOf(SocialProviderUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void 카카오_5xx와_429는_프로바이더_장애다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(SocialProviderUnavailableException.class);

        server.reset();
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withTooManyRequests());
        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(SocialProviderUnavailableException.class);
    }

    @Test
    void 읽을_수_없는_응답은_프로바이더_장애다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(SocialProviderUnavailableException.class);
    }

    @Test
    void aud가_없는_응답은_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess("{\"sub\":\"kakao-12345\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }
}
