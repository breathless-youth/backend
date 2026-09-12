package project.study.room.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.room.dto.RoomJoinResponse;

/**
 * coturn 발급. username = "만료초:userId", credential = HMAC-SHA1(secret,username)..
 */
@Component
class TurnCredentialIssuer {

    private final String secret;
    private final int ttlSeconds;
    private final List<String> urls;

    TurnCredentialIssuer(
            @Value("${app.room.turn.secret:draft-turn-secret}") String secret,
            @Value("${app.room.turn.ttl-seconds:86400}") int ttlSeconds,
            @Value("${app.room.turn.urls:}") List<String> urls) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
        this.urls = urls;
    }

    int ttlSeconds() {
        return ttlSeconds;
    }

    List<RoomJoinResponse.IceServer> forUser(Long userId) {
        if (urls == null || urls.isEmpty() || urls.getFirst().isBlank()) {
            return List.of();
        }
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        String username = expiry + ":" + userId;
        return List.of(new RoomJoinResponse.IceServer(urls, username, hmacSha1(secret, username)));
    }

    private static String hmacSha1(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 계산 실패", e);
        }
    }
}
