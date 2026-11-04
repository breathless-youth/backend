package project.study.user.oauth;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 비어 있는 소셜 로그인 설정을 기동이 끝난 뒤 한 번 로그와 Sentry에 남긴다 (ADR-0042). 값이 비어도 기동은 되고(그룹 릴리즈가 로그인 앱보다
 * 먼저 나간다) 그 프로바이더 로그인만 400이 되는데, 로그인 앱은 그룹 탭에서 로그인을 요구하므로 놓치면 그룹 탭이 통째로 막힌다.
 * sentry-logback이 없어 로그만으로는 아무도 모르므로 Sentry에 직접 남긴다. Sentry가 확실히 초기화된 뒤에 보내도록 빈 생성자가 아니라
 * {@link ApplicationReadyEvent}에서 본다. 값은 싣지 않고 빈 항목의 환경변수 이름만 싣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthConfigCheck {

    private final OAuthProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void reportMissing() {
        List<String> missing = missing(properties);
        if (missing.isEmpty()) {
            return;
        }
        String message = "소셜 로그인 설정이 비어 있어 그 로그인·해지를 모두 거부한다: " + String.join(", ", missing);
        log.warn(message);
        Sentry.captureMessage(message, SentryLevel.WARNING);
    }

    // 고칠 곳이 운영 SSM·태스크 정의라 application-prod.yaml의 환경변수 이름으로 적는다
    static List<String> missing(OAuthProperties properties) {
        OAuthProperties.Apple apple = properties.apple();
        List<String> missing = new ArrayList<>();
        if (properties.google().clientIds().isEmpty()) {
            missing.add("OAUTH_GOOGLE_CLIENT_IDS(구글 로그인)");
        }
        if (properties.kakao().appKeys().isEmpty()) {
            missing.add("OAUTH_KAKAO_APP_KEYS(카카오 로그인)");
        }
        if (!StringUtils.hasText(apple.bundleId())) {
            missing.add("OAUTH_APPLE_BUNDLE_ID(iOS 애플 로그인)");
        }
        if (!StringUtils.hasText(apple.servicesId())) {
            missing.add("OAUTH_APPLE_SERVICES_ID(안드로이드 애플 로그인)");
        }
        if (!StringUtils.hasText(apple.androidCallbackRedirect())) {
            missing.add("OAUTH_APPLE_ANDROID_CALLBACK_REDIRECT(안드로이드 애플 콜백)");
        }
        if (!apple.revokeConfigured()) {
            missing.add("OAUTH_APPLE_TEAM_ID·KEY_ID·PRIVATE_KEY(탈퇴 때 애플 해지)");
        }
        if (!StringUtils.hasText(apple.webRedirectUri())) {
            missing.add("OAUTH_APPLE_WEB_REDIRECT_URI(안드로이드 애플 해지)");
        }
        return missing;
    }
}
