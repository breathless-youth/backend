package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 운영에 OAUTH_* 값을 빠뜨리면 로그인이 400만 내고 조용히 막히므로, 기동 뒤 한 번 Sentry에 남는지 본다 (ADR-0043). */
@ExtendWith(OutputCaptureExtension.class)
class OAuthConfigCheckTest {

    private final MockedStatic<Sentry> sentry = mockStatic(Sentry.class);

    @AfterEach
    void closeSentry() {
        sentry.close();
    }

    @Test
    void 비어_있는_설정을_환경변수_이름으로_로그와_Sentry에_남긴다(CapturedOutput output) {
        new OAuthConfigCheck(new OAuthProperties(null, null, null)).reportMissing();

        sentry.verify(() -> Sentry.captureMessage(
                argThat(message -> message.contains("OAUTH_GOOGLE_CLIENT_IDS")
                        && message.contains("OAUTH_KAKAO_APP_KEYS")
                        && message.contains("OAUTH_APPLE_BUNDLE_IDS")
                        && message.contains("OAUTH_APPLE_SERVICES_ID")
                        && message.contains("OAUTH_APPLE_ANDROID_CALLBACK_REDIRECT")
                        && message.contains("OAUTH_APPLE_TEAM_ID")
                        && message.contains("OAUTH_APPLE_WEB_REDIRECT_URI")),
                eq(SentryLevel.WARNING)));
        assertThat(output).contains("OAUTH_GOOGLE_CLIENT_IDS");
    }

    @Test
    void 채워진_값은_적지_않고_값_자체도_싣지_않는다() {
        OAuthProperties properties = new OAuthProperties(
                new OAuthProperties.Google(List.of("google-web-id")),
                new OAuthProperties.Kakao(List.of("kakao-key")),
                new OAuthProperties.Apple(List.of("com.example.app"), null, null, null, null, null, null));

        new OAuthConfigCheck(properties).reportMissing();

        sentry.verify(() -> Sentry.captureMessage(
                argThat(message -> !message.contains("OAUTH_GOOGLE_CLIENT_IDS")
                        && !message.contains("OAUTH_KAKAO_APP_KEYS")
                        && !message.contains("OAUTH_APPLE_BUNDLE_IDS")
                        && message.contains("OAUTH_APPLE_SERVICES_ID")
                        && !message.contains("google-web-id")
                        && !message.contains("com.example.app")),
                eq(SentryLevel.WARNING)));
    }

    @Test
    void 다_채워져_있으면_아무것도_남기지_않는다() {
        OAuthProperties full = new OAuthProperties(
                new OAuthProperties.Google(List.of("google-web-id")),
                new OAuthProperties.Kakao(List.of("kakao-key")),
                new OAuthProperties.Apple(
                        List.of("com.example.app"),
                        "com.example.web",
                        "TEAM",
                        "KEY",
                        "private-key",
                        "https://api.example.com/api/auth/apple/callback",
                        "https://app.example.com/apple"));

        new OAuthConfigCheck(full).reportMissing();

        sentry.verifyNoInteractions();
    }
}
