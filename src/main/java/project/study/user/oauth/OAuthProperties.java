package project.study.user.oauth;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 소셜 로그인 설정. 허용 aud 목록이 비어도 기동한다 — 운영은 로그인 앱보다 그룹 릴리즈가 먼저 나가 OAUTH_* 값이 없을 수 있다. 빈 목록인
 * 프로바이더의 토큰은 모두 400이고, {@link OAuthConfigCheck}가 기동 뒤 로그와 Sentry에 경고를 남긴다. 섹션이 통째로 없어도 빈 값으로
 * 채운다.
 */
@ConfigurationProperties("oauth")
public record OAuthProperties(Google google, Kakao kakao, Apple apple) {

    public OAuthProperties {
        google = google == null ? new Google(null) : google;
        kakao = kakao == null ? new Kakao(null) : kakao;
        apple = apple == null ? new Apple(null, null, null, null, null, null, null) : apple;
    }

    public record Google(List<String> clientIds) {
        public Google {
            clientIds = audienceList(clientIds);
        }
    }

    public record Kakao(List<String> appKeys) {
        public Kakao {
            appKeys = audienceList(appKeys);
        }
    }

    /** bundleIds는 iOS 앱 번들 ID 목록이다 — 한 서버를 쓰는 앱 변형(개발 서버의 staging·dev 앱)마다 하나. */
    public record Apple(
            List<String> bundleIds,
            String servicesId,
            String teamId,
            String keyId,
            String privateKey,
            String webRedirectUri,
            String androidCallbackRedirect) {

        public Apple {
            bundleIds = audienceList(bundleIds);
        }

        /** ID 토큰의 aud로 허용하는 값: 앱 번들 ID들과 웹(servicesId) 중 비어 있지 않은 것. */
        public List<String> audiences() {
            return Stream.concat(bundleIds.stream(), Stream.of(servicesId).filter(StringUtils::hasText))
                    .toList();
        }

        /** 애플 연결 해제(revoke)에 필요한 키 3종이 모두 있는지. */
        public boolean revokeConfigured() {
            return StringUtils.hasText(teamId) && StringUtils.hasText(keyId) && StringUtils.hasText(privateKey);
        }
    }

    // 쉼표 목록의 앞뒤 공백을 떼고 빈 항목을 버린다 — 빈 문자열이 aud로 허용되지 않게
    private static List<String> audienceList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
