package project.study.room.turn;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.room.dto.RoomJoinResponse.IceServer;

/**
 * 방 입장 응답의 ICE 서버 목록을 만든다 = 자체 coturn(static-auth-secret HMAC 임시 자격) + Cloudflare TURN 폴백(BY-587).
 *
 * <p>클라이언트는 목록의 서버를 모두 ICE 후보 수집에 쓰므로 coturn이 포화돼도 Cloudflare 경로로 릴레이가 이어진다.
 * coturn urls가 비어 있고 Cloudflare도 비활성이면 빈 목록(STUN/TURN 없이 P2P만).
 */
@Component
public class IceServerFactory {

    private final String turnSecret;
    private final int ttlSeconds;
    private final List<String> turnUrls;
    private final CloudflareTurnCredentialProvider cloudflare;
    private final boolean cloudflarePrimary;

    public IceServerFactory(
            @Value("${app.room.turn.secret:draft-turn-secret}") String turnSecret,
            @Value("${app.room.turn.ttl-seconds:86400}") int ttlSeconds,
            @Value("${app.room.turn.urls:}") List<String> turnUrls,
            @Value("${app.room.turn.cloudflare.primary:true}") boolean cloudflarePrimary,
            CloudflareTurnCredentialProvider cloudflare) {
        this.turnSecret = turnSecret;
        this.ttlSeconds = ttlSeconds;
        this.turnUrls = turnUrls;
        this.cloudflarePrimary = cloudflarePrimary;
        this.cloudflare = cloudflare;
    }

    /** 클라이언트에 알려주는 자격 유효 시간(초) — coturn 자격의 만료와 같다 */
    public int ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 목록 순서 = 같은 릴레이 후보끼리의 ICE 로컬 우선순위. {@code app.room.turn.cloudflare.primary=true}(기본)면
     * Cloudflare를 앞에 둬 평상시 릴레이를 Cloudflare가 받고 coturn은 백업이 된다 (coturn 무료 VM의 인터넷 방향
     * 50Mbps 캡 때문). false면 coturn 우선.
     */
    public List<IceServer> forUser(Long userId) {
        List<IceServer> cf = cloudflare.iceServers();
        List<IceServer> coturn = new ArrayList<>();
        if (turnUrls != null && !turnUrls.isEmpty() && !turnUrls.getFirst().isBlank()) {
            // coturn use-auth-secret 규약: username = "<만료 epoch초>:<식별자>", credential = base64(HMAC-SHA1(secret,
            // username))
            long expiry = Instant.now().getEpochSecond() + ttlSeconds;
            String username = expiry + ":" + userId;
            coturn.add(new IceServer(turnUrls, username, hmacSha1(turnSecret, username)));
        }
        List<IceServer> servers = new ArrayList<>();
        if (cloudflarePrimary) {
            servers.addAll(cf);
            servers.addAll(coturn);
        } else {
            servers.addAll(coturn);
            servers.addAll(cf);
        }
        return List.copyOf(servers);
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
