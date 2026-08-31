package project.study.studysession.buffer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;

/**
 * 인메모리 버퍼 저장소 (기본값). 단일 인스턴스 전제 — 크래시 시 마지막 flush 이후 대기분이 유실될 수 있으나,
 * 클라이언트가 다음 틱에 누적 스냅샷을 통째로 재전송하므로 실질 손실은 없다 (BY-470 설계 참고).
 */
@Component
@ConditionalOnProperty(name = "active-session.buffer.store", havingValue = "memory", matchIfMissing = true)
public class InMemorySnapshotBufferStore implements SnapshotBufferStore {

    private final ConcurrentHashMap<Key, PendingSnapshot> pending = new ConcurrentHashMap<>();

    private record Key(Long userId, Instant startedAt) {}

    @Override
    public void offer(ActiveSessionSnapshotRequest request, Instant lastSeenAt) {
        Key key = new Key(request.userId(), request.startedAt());
        PendingSnapshot incoming = new PendingSnapshot(request, lastSeenAt);
        pending.merge(
                key,
                incoming,
                (current, next) ->
                        next.request().reportedAt().isAfter(current.request().reportedAt()) ? next : current);
    }

    @Override
    public List<PendingSnapshot> drainAll() {
        List<PendingSnapshot> out = new ArrayList<>();
        for (Key key : List.copyOf(pending.keySet())) {
            PendingSnapshot p = pending.remove(key);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public void ackDrained() {
        // 인메모리는 drain이 곧 제거라 ack가 따로 없다 — 크래시 유실은 클라이언트 누적 재전송이 메운다
    }

    @Override
    public int pendingCount() {
        return pending.size();
    }
}
