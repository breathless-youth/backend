package project.study.room.turn;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import project.study.room.dto.RoomJoinResponse.IceServer;
import tools.jackson.databind.JsonNode;

/**
 * Cloudflare Realtime TURN 임시 자격 제공자 (BY-587).
 *
 * <p>자체 coturn(무료 VM)은 유입 패킷 처리율 한계가 낮아, 방 입장 응답의 ICE 서버 목록에 Cloudflare TURN을 함께
 * 내려줘 클라이언트가 붙는 쪽으로 릴레이를 이어가게 한다. 자격은 TURN Key API로 발급받되 사용자별로 묶이지 않으므로
 * 한 벌을 캐시해 공유하고, TTL의 절반이 지나면 갱신한다.
 *
 * <p>폴백이 새 장애점이 되면 안 되므로 Cloudflare 호출 실패는 예외로 전파하지 않는다 — 아직 유효한 캐시가 있으면
 * 그대로 내려주고, 없으면 빈 목록(coturn만)으로 응답한다. key-id·api-token이 비어 있으면 비활성이라 기존 동작과
 * 동일하다.
 */
@Slf4j
@Component
public class CloudflareTurnCredentialProvider {

    private final RestClient restClient;
    private final String keyId;
    private final int ttlSeconds;
    private final boolean enabled;
    private final Clock clock;
    private final AtomicReference<Cached> cache = new AtomicReference<>();
    /** 발급 실패 후 재시도 간격 — 토큰 오류·장애 중에도 방 입장마다 외부 호출(최대 타임아웃 3s)이 반복되지 않게 */
    static final long FAILURE_BACKOFF_SECONDS = 60;

    private record Cached(List<IceServer> servers, Instant refreshAfter, Instant expiresAt) {}

    public CloudflareTurnCredentialProvider(
            @Qualifier(CloudflareTurnConfig.REST_CLIENT) RestClient restClient,
            @Value("${app.room.turn.cloudflare.key-id:}") String keyId,
            @Value("${app.room.turn.cloudflare.api-token:}") String apiToken,
            @Value("${app.room.turn.cloudflare.ttl-seconds:86400}") int ttlSeconds,
            Clock clock) {
        this.restClient = restClient;
        this.keyId = keyId;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.enabled = StringUtils.hasText(keyId) && StringUtils.hasText(apiToken);
        if (!enabled) {
            log.info("Cloudflare TURN 폴백 비활성 (key-id/api-token 미설정)");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 현재 유효한 Cloudflare ICE 서버 목록. 비활성이거나 발급 실패(캐시 없음)면 빈 목록. */
    public List<IceServer> iceServers() {
        if (!enabled) {
            return List.of();
        }
        Cached cached = cache.get();
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.refreshAfter()) && now.isBefore(cached.expiresAt())) {
            return cached.servers();
        }
        return refresh(now);
    }

    private synchronized List<IceServer> refresh(Instant now) {
        Cached cached = cache.get();
        if (cached != null && now.isBefore(cached.refreshAfter()) && now.isBefore(cached.expiresAt())) {
            return cached.servers(); // 다른 스레드가 방금 갱신함
        }
        try {
            JsonNode root = restClient
                    .post()
                    .uri("/v1/turn/keys/{keyId}/credentials/generate-ice-servers", keyId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ttl", ttlSeconds))
                    .retrieve()
                    .body(JsonNode.class);
            List<IceServer> servers = parse(root);
            if (servers.isEmpty()) {
                throw new IllegalStateException("iceServers 비어 있음: " + root);
            }
            cache.set(new Cached(servers, now.plusSeconds(ttlSeconds / 2L), now.plusSeconds(ttlSeconds)));
            return servers;
        } catch (RuntimeException e) {
            // RestClientException·Jackson 파싱 오류(unchecked)·형식 오류 전부 여기서 흡수 — 폴백이 장애점이 되면 안 된다.
            // 실패 후 60초는 재호출하지 않는다 (만료 전 캐시가 있으면 그걸, 없으면 빈 목록을 그동안 내려준다)
            Instant retryAt = now.plusSeconds(FAILURE_BACKOFF_SECONDS);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                log.warn("Cloudflare TURN 자격 갱신 실패 — 만료 전 캐시로 계속 응답: {}", e.getMessage());
                // 백오프가 만료를 넘기지 않게 — 만료 뒤엔 (실패해도) 빈 목록으로 떨어져야 한다
                Instant cappedRetry = retryAt.isBefore(cached.expiresAt()) ? retryAt : cached.expiresAt();
                cache.set(new Cached(cached.servers(), cappedRetry, cached.expiresAt()));
                return cached.servers();
            }
            log.warn("Cloudflare TURN 자격 발급 실패 — coturn만으로 응답: {}", e.getMessage());
            cache.set(new Cached(List.of(), retryAt, retryAt)); // 빈 결과도 백오프 동안 유효(재호출 억제)
            return List.of();
        }
    }

    /**
     * 응답 형식 두 가지를 모두 받는다: {@code {"iceServers": {urls, username, credential}}} (단일 객체) 또는
     * {@code {"iceServers": [{...}, ...]}} (배열).
     */
    private static List<IceServer> parse(JsonNode root) {
        if (root == null || root.get("iceServers") == null) {
            return List.of();
        }
        JsonNode node = root.get("iceServers");
        List<IceServer> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> addIfValid(out, n));
        } else {
            addIfValid(out, node);
        }
        return List.copyOf(out);
    }

    private static void addIfValid(List<IceServer> out, JsonNode n) {
        JsonNode urls = n.get("urls");
        if (urls == null || !urls.isArray() || urls.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>();
        urls.forEach(u -> list.add(u.asString()));
        String username = n.path("username").asString(null);
        String credential = n.path("credential").asString(null);
        out.add(new IceServer(List.copyOf(list), username, credential));
    }
}
