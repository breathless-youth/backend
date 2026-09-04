package project.study.room.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import project.study.room.dto.RoomJoinResponse;

/**
 * coturn 시한부 자격(RFC 8489 ephemeral credential) 발급. username = "만료초:userId", credential = HMAC-SHA1(secret,
 * username). 룸 상태 관리(RoomService)와 관심사가 달라 분리했다.
 */
class TurnCredentialIssuer {

    private final String secret;
    private final int ttlSeconds;
    private final List<String> urls;

    TurnCredentialIssuer(String secret, int ttlSeconds, List<String> urls) {
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
