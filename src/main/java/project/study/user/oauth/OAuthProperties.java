package project.study.user.oauth;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties("oauth")
public record OAuthProperties(Google google, Kakao kakao, Apple apple) {

    public record Google(List<String> clientIds) {}

    public record Kakao(List<String> appKeys) {}

    public record Apple(
            String bundleId,
            String servicesId,
            String teamId,
            String keyId,
            String privateKey,
            String webRedirectUri,
            String androidCallbackRedirect) {

        /** ID 토큰의 aud로 허용하는 값: 앱(bundleId)과 웹(servicesId) 중 비어 있지 않은 것. */
        public List<String> audiences() {
            return Stream.of(bundleId, servicesId).filter(StringUtils::hasText).toList();
        }

        /** 애플 연결 해제(revoke)에 필요한 키 3종이 모두 있는지. */
        public boolean revokeConfigured() {
            return StringUtils.hasText(teamId) && StringUtils.hasText(keyId) && StringUtils.hasText(privateKey);
        }
    }
}
