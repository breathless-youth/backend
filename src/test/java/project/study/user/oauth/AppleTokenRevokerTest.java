package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.user.dto.AppleClient;

class AppleTokenRevokerTest {

    private static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    private static final String REVOKE_URL = "https://appleid.apple.com/auth/revoke";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();

    @Test
    void IOS는_bundleId로_교환하고_redirect_uri_없이_해지한다() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("client_id=com.example.app")))
                .andExpect(content().string(containsString("code=the-code")))
                .andExpect(content().string(containsString("grant_type=authorization_code")))
                .andExpect(content().string(not(containsString("redirect_uri"))))
                .andRespond(withSuccess("{\"refresh_token\":\"r-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REVOKE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("client_id=com.example.app")))
                .andExpect(content().string(containsString("token=r-token")))
                .andExpect(content().string(containsString("token_type_hint=refresh_token")))
                .andRespond(withSuccess());

        revoker(true).revoke("the-code", AppleClient.IOS);

        server.verify();
    }

    @Test
    void ANDROID는_servicesId와_redirect_uri로_교환한다() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().string(containsString("client_id=com.example.web")))
                .andExpect(content().string(containsString("redirect_uri=https")))
                .andRespond(withSuccess("{\"refresh_token\":\"r-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REVOKE_URL))
                .andExpect(content().string(containsString("client_id=com.example.web")))
                .andRespond(withSuccess());

        revoker(true).revoke("the-code", AppleClient.ANDROID);

        server.verify();
    }

    @Test
    void 설정이_없으면_HTTP를_부르지_않는다() {
        server.expect(never(), requestTo(TOKEN_URL)).andRespond(withSuccess());
        server.expect(never(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        assertThatCode(() -> revoker(false).revoke("the-code", AppleClient.IOS)).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void 토큰_교환이_400이면_해지_없이_예외_없이_끝난다() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(never(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        assertThatCode(() -> revoker(true).revoke("the-code", AppleClient.IOS)).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void 해지가_500이어도_예외가_나지_않는다() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"refresh_token\":\"r-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REVOKE_URL)).andRespond(withServerError());

        assertThatCode(() -> revoker(true).revoke("the-code", AppleClient.IOS)).doesNotThrowAnyException();

        server.verify();
    }

    private AppleTokenRevoker revoker(boolean configured) {
        OAuthProperties.Apple apple = new OAuthProperties.Apple(
                "com.example.app",
                "com.example.web",
                configured ? "TEAMID1234" : "",
                configured ? "KEYID12345" : "",
                configured ? pem() : "",
                "https://example.com/callback",
                "");
        OAuthProperties properties = new OAuthProperties(null, null, apple);
        return new AppleTokenRevoker(builder, properties, new AppleClientSecret(properties));
    }

    private static String pem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            String body = Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
