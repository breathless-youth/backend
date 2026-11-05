package project.study.user.oauth;

import java.time.Duration;
import java.time.Instant;
import project.study.user.entity.Provider;

/**
 * 검증한 ID 토큰의 내용. nonce는 nonce 클레임이며 없으면 null이다. expiresAt은 토큰의 exp — 한 번 쓴 토큰의 기록을 언제까지 둘지 정하는 데
 * 쓰고, 프로바이더가 exp를 주지 않으면 지금부터 하루로 둔다.
 */
public record OAuthUserInfo(Provider provider, String providerUserId, String email, String nonce, Instant expiresAt) {

    static final Duration DEFAULT_TTL = Duration.ofDays(1);

    public OAuthUserInfo {
        if (expiresAt == null) {
            expiresAt = Instant.now().plus(DEFAULT_TTL);
        }
    }
}
