package project.study.point.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 충전 알림을 받는 설정. RevenueCat 대시보드의 웹훅에 넣은 Authorization 값과 같은 값을 서버에 둔다. 값이 비어 있으면 충전은 꺼진 것이다
 * (웹훅 404). 샌드박스 결제는 실제 돈이 나가지 않으므로 dev 서버에서만 받는다 — 운영에서 켜면 테스트 계정으로 포인트를 공짜로 받는다.
 */
@Component
public class ChargePolicy {

    private final byte[] webhookAuth;
    private final boolean acceptSandbox;

    public ChargePolicy(
            @Value("${app.points.charge.revenuecat-webhook-auth:}") String webhookAuth,
            @Value("${app.points.charge.accept-sandbox:false}") boolean acceptSandbox) {
        this.webhookAuth = webhookAuth.trim().getBytes(StandardCharsets.UTF_8);
        this.acceptSandbox = acceptSandbox;
    }

    public boolean available() {
        return webhookAuth.length > 0;
    }

    /** 걸린 시간으로 값을 짐작하지 못하게 일정한 시간으로 견준다. */
    public boolean authorized(String authorizationHeader) {
        return authorizationHeader != null
                && MessageDigest.isEqual(webhookAuth, authorizationHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    public boolean acceptSandbox() {
        return acceptSandbox;
    }
}
