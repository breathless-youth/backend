package project.study.studysession.buffer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis write-behind 버퍼 저장소 (BY-492).
 *
 * <p>인메모리 저장소와 달리 앱 크래시·재배포에도 대기분이 남고, 다중 태스크 확장 시에도 버퍼가 갈라지지
 * 않는다 (동시접속 5000명 로드맵 선행).
 *
 * <p>저장 구조: 해시 {@code {active:snapshot}:buffer}, field = {@code userId:startedAt(ISO)},
 * value = {@code ts19|json}. ts19는 reportedAt의 epochSecond(10자리)+nano(9자리) 고정폭 문자열로,
 * 같은 길이의 숫자 문자열 비교 = 수치 비교라 Lua에서 나노 정밀도 그대로 최신 여부를 원자 판정한다
 * (나노 epoch 숫자는 Lua 정수 한계 2^53을 넘어 숫자 비교가 불가).
 *
 * <p>drain은 at-least-once: {@code RENAMENX}로 버퍼를 draining 해시로 원자 분리해 읽고(삭제 안 함),
 * DB 반영 후 {@link #ackDrained()}가 지운다. flush 도중 크래시하면 다음 drain이 잔여 draining 해시를
 * 재전달한다 — DB UPSERT가 멱등({@code WHERE reported_at < excluded})이라 중복 재전달은 무해하다.
 * {@code RENAMENX}는 다른 flusher가 처리 중인 배치를 덮어쓰지 않아 다중 태스크에서도 유실이 없다.
 * 키의 {@code {active:snapshot}} hash tag는 클러스터 전환 시 RENAME의 same-slot 조건을 지켜준다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "active-session.buffer.store", havingValue = "redis")
public class RedisSnapshotBufferStore implements SnapshotBufferStore {

    static final String BUFFER_KEY = "{active:snapshot}:buffer";
    static final String DRAIN_KEY = BUFFER_KEY + ":draining";

    // 저장값의 ts19 프리픽스(고정폭 19자)와 비교해 최신일 때만 덮어쓴다 — 역순 도착 무시를 원자화
    private static final RedisScript<Long> OFFER_SCRIPT = RedisScript.of("""
            local cur = redis.call('HGET', KEYS[1], ARGV[1])
            if cur == false or string.sub(cur, 1, 19) < ARGV[3] then
              redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void offer(ActiveSessionSnapshotRequest request, Instant lastSeenAt) {
        String field = request.userId() + ":" + request.startedAt();
        String ts19 = fixedWidthTimestamp(request.reportedAt());
        String json = objectMapper.writeValueAsString(new StoredSnapshot(request, lastSeenAt));
        redis.execute(OFFER_SCRIPT, List.of(BUFFER_KEY), field, ts19 + "|" + json, ts19);
    }

    /** epochSecond 10자리 + nano 9자리 고정폭 — 서비스 검증이 미래·역행을 걸러 1970년 이전은 오지 않는다(음수는 0으로 클램프). */
    private static String fixedWidthTimestamp(Instant reportedAt) {
        return "%010d%09d".formatted(Math.max(0, reportedAt.getEpochSecond()), reportedAt.getNano());
    }

    @Override
    public List<PendingSnapshot> drainAll() {
        // 이전 flush 크래시(ack 못 함)·다른 flusher의 잔여 배치를 먼저 재전달
        List<PendingSnapshot> leftover = readAll(DRAIN_KEY);
        if (!leftover.isEmpty()) {
            return leftover;
        }
        if (Boolean.TRUE.equals(redis.hasKey(BUFFER_KEY))
                && Boolean.TRUE.equals(redis.renameIfAbsent(BUFFER_KEY, DRAIN_KEY))) {
            return readAll(DRAIN_KEY);
        }
        return List.of(); // 버퍼 비었거나, 다른 flusher가 draining 중(RENAMENX 실패)
    }

    @Override
    public void ackDrained() {
        redis.delete(DRAIN_KEY);
    }

    private List<PendingSnapshot> readAll(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        List<PendingSnapshot> out = new ArrayList<>(entries.size());
        for (Object value : entries.values()) {
            String raw = (String) value;
            String json = raw.substring(raw.indexOf('|') + 1);
            StoredSnapshot stored = objectMapper.readValue(json, StoredSnapshot.class);
            out.add(new PendingSnapshot(stored.request(), stored.lastSeenAt()));
        }
        return out;
    }

    @Override
    public int pendingCount() {
        // ack 안 된 draining 분도 대기분이다 (같은 세션이 양쪽에 있으면 소폭 과대집계될 수 있으나 모니터링 용도로 충분)
        Long buffered = redis.opsForHash().size(BUFFER_KEY);
        Long draining = redis.opsForHash().size(DRAIN_KEY);
        return (int) ((buffered == null ? 0 : buffered) + (draining == null ? 0 : draining));
    }

    record StoredSnapshot(ActiveSessionSnapshotRequest request, Instant lastSeenAt) {}
}
