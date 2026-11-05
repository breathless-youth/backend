package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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
    // 해지 실패는 로그만으로는 아무도 모른다(sentry-logback 없음) — 이 코드베이스 관례대로 Sentry에 직접 남는지 본다
    private final MockedStatic<Sentry> sentry = mockStatic(Sentry.class);

    @AfterEach
    void closeSentry() {
        sentry.close();
    }

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
        sentry.verifyNoInteractions();
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
        assertSentry(SentryLevel.WARNING, "IOS");
    }

    @Test
    void 토큰_교환이_400이면_해지_없이_예외_없이_끝난다() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(never(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        assertThatCode(() -> revoker(true).revoke("the-code", AppleClient.IOS)).doesNotThrowAnyException();

        server.verify();
        assertSentry(SentryLevel.ERROR, "IOS");
    }

    @Test
    void 교환_응답에_refresh_token이_없으면_Sentry에_남긴다() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(never(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        assertThatCode(() -> revoker(true).revoke("the-code", AppleClient.ANDROID))
                .doesNotThrowAnyException();

        server.verify();
        assertSentry(SentryLevel.ERROR, "ANDROID");
    }

    @Test
    void 앱_종류가_없으면_교환하지_않고_Sentry에_남긴다() {
        server.expect(never(), requestTo(TOKEN_URL)).andRespond(withSuccess());

        assertThatCode(() -> revoker(true).revoke("the-code", null)).doesNotThrowAnyException();

        server.verify();
        assertSentry(SentryLevel.ERROR, "null");
    }

    @Test
    void 해지가_500이어도_예외가_나지_않는다() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"refresh_token\":\"r-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REVOKE_URL)).andRespond(withServerError());

        assertThatCode(() -> revoker(true).revoke("the-code", AppleClient.IOS)).doesNotThrowAnyException();

        server.verify();
        assertSentry(SentryLevel.ERROR, "IOS");
    }

    @Test
    void 번들_ID가_여럿이면_400이_난_다음_번들로_교환해_해지한다() {
        // 개발 서버는 staging·dev 앱이 함께 쓰는데 요청은 어느 앱의 코드인지 알려 주지 않는다
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().string(containsString("client_id=com.example.app&")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().string(containsString("client_id=com.example.app.dev")))
                .andRespond(withSuccess("{\"refresh_token\":\"r-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REVOKE_URL))
                .andExpect(content().string(containsString("client_id=com.example.app.dev")))
                .andRespond(withSuccess());

        revoker(true, List.of("com.example.app", "com.example.app.dev")).revoke("the-code", AppleClient.IOS);

        server.verify();
        sentry.verifyNoInteractions();
    }

    @Test
    void 교환이_500이면_다음_번들로_넘어가지_않는다() {
        // 코드가 틀린 게 아니라 애플 장애다 — 다른 번들로 다시 해 봐야 소용없다
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatCode(() -> revoker(true, List.of("com.example.app", "com.example.app.dev"))
                        .revoke("the-code", AppleClient.IOS))
                .doesNotThrowAnyException();

        server.verify();
        assertSentry(SentryLevel.ERROR, "IOS");
    }

    @Test
    void 번들_ID가_다_400이면_Sentry에_한_번_남긴다() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(never(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        revoker(true, List.of("com.example.app", "com.example.app.dev")).revoke("the-code", AppleClient.IOS);

        server.verify();
        assertSentry(SentryLevel.ERROR, "IOS");
    }

    @Test
    void 이_앱_종류의_클라이언트_ID가_없으면_교환하지_않는다() {
        server.expect(never(), requestTo(TOKEN_URL)).andRespond(withSuccess());

        revoker(true, List.of()).revoke("the-code", AppleClient.IOS);

        server.verify();
        assertSentry(SentryLevel.ERROR, "IOS");
    }

    // 고정 문구 + 앱 종류만 — 인가 코드·client secret·애플 토큰은 싣지 않는다
    private void assertSentry(SentryLevel level, String client) {
        sentry.verify(() -> Sentry.captureMessage(
                argThat(message -> message.startsWith("애플 토큰 해지")
                        && message.contains("client=" + client)
                        && !message.contains("the-code")
                        && !message.contains("r-token")),
                eq(level)));
    }

    private AppleTokenRevoker revoker(boolean configured) {
        return revoker(configured, List.of("com.example.app"));
    }

    private AppleTokenRevoker revoker(boolean configured, List<String> bundleIds) {
        OAuthProperties.Apple apple = new OAuthProperties.Apple(
                bundleIds,
                "com.example.web",
                configured ? "TEAMID1234" : "",
                configured ? "KEYID12345" : "",
                configured ? pem() : "",
                "https://example.com/callback",
                "");
        OAuthProperties properties = new OAuthProperties(null, null, apple);
        return new AppleTokenRevoker(builder.build(), properties, new AppleClientSecret(properties));
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
