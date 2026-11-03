package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.entity.Provider;

class GoogleTokenVerifierTest {

    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=id-token";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final GoogleTokenVerifier verifier = new GoogleTokenVerifier(
            builder,
            new OAuthProperties(new OAuthProperties.Google(List.of("my-client-id", "second-client-id")), null, null));

    @Test
    void 유효한_ID_토큰이면_유저정보를_반환한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-client-id\",\"sub\":\"12345\",\"email\":\"a@b.com\"}",
                        MediaType.APPLICATION_JSON));

        OAuthUserInfo userInfo = verifier.verify("id-token");

        assertThat(userInfo.provider()).isEqualTo(Provider.GOOGLE);
        assertThat(userInfo.providerUserId()).isEqualTo("12345");
        assertThat(userInfo.nonce()).isNull();
    }

    @Test
    void 목록의_두번째_aud로_발급된_토큰도_통과한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"second-client-id\",\"sub\":\"12345\",\"email\":\"a@b.com\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").providerUserId()).isEqualTo("12345");
    }

    @Test
    void 토큰의_nonce를_그대로_담는다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-client-id\",\"sub\":\"12345\",\"nonce\":\"abc-nonce\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").nonce()).isEqualTo("abc-nonce");
    }

    @Test
    void 문자열로_온_exp를_만료_시각으로_담는다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"my-client-id\",\"sub\":\"12345\",\"exp\":\"1700000000\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").expiresAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void exp가_없으면_하루_뒤를_만료_시각으로_둔다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess("{\"aud\":\"my-client-id\",\"sub\":\"12345\"}", MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("id-token").expiresAt())
                .isBetween(
                        Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
    }

    @Test
    void 목록에_없는_aud는_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(
                        "{\"aud\":\"other-app-client-id\",\"sub\":\"12345\",\"email\":\"a@b.com\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 구글이_거부한_토큰은_예외를_던진다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 네트워크_실패는_InvalidOAuthTokenException으로_바꾼다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void aud가_없는_응답은_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess("{\"sub\":\"12345\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
    }
}
