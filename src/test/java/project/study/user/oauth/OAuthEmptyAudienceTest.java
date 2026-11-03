package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 운영은 로그인 앱보다 그룹 릴리즈가 먼저 나가므로 OAUTH_* 없이도 떠야 한다. 허용 aud 목록이 비면 그 프로바이더 토큰은 모두 400이고, 기동 때
 * 프로바이더마다 경고를 한 번 남긴다.
 */
@ExtendWith(OutputCaptureExtension.class)
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
    void 빈_값이면_빈_목록으로_묶이고_기동하며_프로바이더마다_경고를_남긴다(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OAuthProperties properties = context.getBean(OAuthProperties.class);
            assertThat(properties.google().clientIds()).isEmpty();
            assertThat(properties.kakao().appKeys()).isEmpty();
            assertThat(properties.apple().audiences()).isEmpty();
        });
        assertThat(output)
                .contains("oauth.google.client-ids")
                .contains("oauth.kakao.app-keys")
                .contains("oauth.apple.bundle-id");
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
