package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 운영은 로그인 앱보다 그룹 릴리즈가 먼저 나가므로 OAUTH_* 없이도 떠야 한다. 허용 aud 목록이 비면 그 프로바이더 토큰은 모두 400이다(빈 값
 * 경고는 {@link OAuthConfigCheckTest}).
 */
class OAuthEmptyAudienceTest {

    // application-prod.yaml의 ${OAUTH_GOOGLE_CLIENT_IDS:} 등이 비었을 때와 같은 값
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Verifiers.class)
            .withPropertyValues(
                    "oauth.google.client-ids=",
                    "oauth.kakao.app-keys=",
                    "oauth.apple.bundle-id=",
                    "oauth.apple.services-id=");

    @Test
    void 빈_값이면_빈_목록으로_묶이고_기동한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OAuthProperties properties = context.getBean(OAuthProperties.class);
            assertThat(properties.google().clientIds()).isEmpty();
            assertThat(properties.kakao().appKeys()).isEmpty();
            assertThat(properties.apple().audiences()).isEmpty();
        });
    }

    @Test
    void oauth_설정이_아예_없어도_기동한다() {
        new ApplicationContextRunner().withUserConfiguration(Verifiers.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OAuthProperties.class).google().clientIds())
                    .isEmpty();
        });
    }

    @Test
    void 운영_yaml은_목록형_이름이_없으면_바뀌기_전_이름의_값을_읽는다() throws IOException {
        // 운영에는 목록형으로 바뀌기 전 이름(OAUTH_GOOGLE_CLIENT_ID 등)으로 값이 이미 들어가 있다 — 다시 넣지 않아도 로그인이 열린다
        MockEnvironment old = prodYaml();
        old.setProperty("OAUTH_GOOGLE_CLIENT_ID", "old-web-id");
        old.setProperty("OAUTH_KAKAO_APP_KEY", "old-kakao-key");
        old.setProperty("OAUTH_APPLE_CLIENT_ID", "com.example.app");
        assertThat(old.getProperty("oauth.google.client-ids")).isEqualTo("old-web-id");
        assertThat(old.getProperty("oauth.kakao.app-keys")).isEqualTo("old-kakao-key");
        assertThat(old.getProperty("oauth.apple.bundle-id")).isEqualTo("com.example.app");

        // 목록형 이름이 있으면 그쪽이 이긴다
        old.setProperty("OAUTH_GOOGLE_CLIENT_IDS", "web-id,ios-id");
        assertThat(old.getProperty("oauth.google.client-ids")).isEqualTo("web-id,ios-id");

        // 둘 다 없으면 빈 값 — 기동은 되고 그 로그인만 막힌다
        assertThat(prodYaml().getProperty("oauth.google.client-ids")).isEmpty();
    }

    private static MockEnvironment prodYaml() throws IOException {
        MockEnvironment env = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application-prod", new ClassPathResource("application-prod.yaml"))
                .forEach(env.getPropertySources()::addLast);
        return env;
    }

    @Test
    void 쉼표_목록은_앞뒤_공백을_떼고_빈_항목은_버린다() {
        runner.withPropertyValues("oauth.google.client-ids= web-id , ios-id ,")
                .run(context -> assertThat(
                                context.getBean(OAuthProperties.class).google().clientIds())
                        .containsExactly("web-id", "ios-id"));
    }

    @Test
    void 허용_aud가_비면_프로바이더를_부르지_않고_400이다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(never(), anything()).andRespond(withSuccess());
        OAuthProperties empty = new OAuthProperties(null, null, null);

        for (OAuthTokenVerifier verifier : List.of(
                new GoogleTokenVerifier(builder.build(), empty),
                new KakaoTokenVerifier(builder.build(), empty),
                new AppleTokenVerifier(builder.build(), empty))) {
            assertThatThrownBy(() -> verifier.verify("id-token"))
                    .as(verifier.provider().name())
                    .isInstanceOf(InvalidOAuthTokenException.class);
        }
        server.verify();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OAuthProperties.class)
    @Import({GoogleTokenVerifier.class, KakaoTokenVerifier.class, AppleTokenVerifier.class})
    static class Verifiers {

        @Bean(SocialProviderHttpConfig.REST_CLIENT)
        RestClient socialProviderRestClient() {
            return RestClient.create();
        }
    }
}
