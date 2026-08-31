package project.study.studysession.buffer;

import java.time.Instant;
import java.util.List;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;

/**
 * 스냅샷 코얼레싱 버퍼의 저장소 추상화 (BY-492).
 *
 * <p>{@code active-session.buffer.store} 설정으로 구현을 고른다 — {@code memory}(기본, 인메모리) 또는 {@code redis}
 * (write-behind, 재기동·크래시에도 대기분 유지). 코얼레싱 규칙은 구현이 지킨다: 같은 세션(userId, startedAt)은 최신본만
 * 유지하고, reportedAt 역순 도착은 무시한다.
 *
 * <p>drain은 at-least-once 계약이다: {@link #drainAll()}로 꺼낸 배치는 {@link #ackDrained()}가 호출되기 전까지
 * 저장소에 남아, flush 도중 크래시하면 다음 drain이 같은 배치를 재전달한다. 소비 측(DB UPSERT)이 멱등이므로 중복
 * 재전달은 무해하다.
 */
public interface SnapshotBufferStore {

    /** 스냅샷 1건을 넣는다. 같은 세션의 이전 스냅샷은 덮어쓰고, reportedAt이 더 이른 스냅샷은 무시한다. */
    void offer(ActiveSessionSnapshotRequest request, Instant lastSeenAt);

    /**
     * 대기 배치를 꺼낸다(저장소에서 즉시 삭제하지 않음). 반환된 배치를 소비(DB 반영)한 뒤 {@link #ackDrained()}를
     * 호출해야 삭제된다. drain 이후 들어온 스냅샷은 다음 사이클에 반영된다.
     */
    List<PendingSnapshot> drainAll();

    /** 직전 {@link #drainAll()} 배치의 소비 완료를 확정하고 저장소에서 지운다. */
    void ackDrained();

    /** 대기 중 세션 수(ack 안 된 drain 분 포함) — 모니터링·테스트용. */
    int pendingCount();

    record PendingSnapshot(ActiveSessionSnapshotRequest request, Instant lastSeenAt) {}
}
