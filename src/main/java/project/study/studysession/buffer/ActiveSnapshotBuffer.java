package project.study.studysession.buffer;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.repository.ActiveStudySessionBatchRepository;
import project.study.studysession.repository.ActiveStudySessionBatchRepository.SnapshotRow;
import tools.jackson.databind.ObjectMapper;

/**
 * 진행중 세션 스냅샷의 코얼레싱 버퍼 (BY-470).
 *
 * <p>체크포인트 주기가 짧아지면(1초 계획) 요청마다 DB에 UPSERT하는 비용(파싱·직렬화·JDBC·GC)이
 * CPU와 Hikari 풀을 포화시킨다. 스냅샷은 (userId, startedAt) 단위 누적 멱등본이라, 중간 스냅샷은
 * 어차피 최신본에 덮어써진다 — 그래서 메모리에 <b>세션당 최신본만</b> 유지하고(코얼레싱),
 * {@link #flush()}에서 한 배치로 UPSERT한다. reportedAt이 큰 것만 남겨 역순 도착을 무시한다
 * (기존 UPSERT의 {@code WHERE reported_at < excluded} 가드와 같은 의미).
 *
 * <p>트레이드오프: 버퍼는 메모리라 앱 크래시 시 마지막 flush 이후 스냅샷이 유실될 수 있다. 다만
 * 클라이언트가 다음 틱에 누적 스냅샷을 통째로 재전송하므로 실질 손실은 없다. 내구성이 더 필요하면
 * 저장소를 Redis(write-behind)로 바꾸면 된다 — 이 클래스만 교체하면 나머지는 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveSnapshotBuffer {

    private final ActiveStudySessionBatchRepository batchRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();

    private record Key(Long userId, Instant startedAt) {}

    private record Pending(ActiveSessionSnapshotRequest request, Instant lastSeenAt) {}

    /**
     * 스냅샷을 버퍼에 넣는다. 같은 세션의 이전 스냅샷은 덮어써지고(코얼레싱), reportedAt이 더
     * 이른(역순 도착) 스냅샷은 무시된다. 검증은 호출 측(서비스)에서 이미 끝난 상태다.
     */
    public void offer(ActiveSessionSnapshotRequest request) {
        Key key = new Key(request.userId(), request.startedAt());
        Pending incoming = new Pending(request, clock.instant());
        pending.merge(
                key,
                incoming,
                (current, next) ->
                        next.request().reportedAt().isAfter(current.request().reportedAt()) ? next : current);
    }

    /** 현재 대기 중인 세션 수 — 모니터링·테스트용. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 모아둔 최신본들을 한 배치로 UPSERT한다. flush 도중 들어온 더 최신 스냅샷은 remove가 지우지
     * 않고 남겨 다음 사이클에 반영된다. 배치가 실패하면(예: 존재하지 않는 user_id의 FK 위반) 한 건씩
     * 재시도해 나쁜 행만 건너뛴다 — 한 poison 행이 전체 flush를 막지 않게 한다.
     */
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<SnapshotRow> rows = new java.util.ArrayList<>();
        for (Key key : List.copyOf(pending.keySet())) {
            Pending p = pending.remove(key);
            if (p == null) {
                continue;
            }
            ActiveSessionSnapshotRequest r = p.request();
            String eventsJson = objectMapper.writeValueAsString(r.events());
            rows.add(new SnapshotRow(
                    r.userId(), r.startedAt(), r.reportedAt(), p.lastSeenAt(), r.studySec(), r.focusSec(), eventsJson));
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            batchRepository.bulkUpsert(rows);
        } catch (Exception e) {
            log.error("스냅샷 벌크 flush 실패 — 한 건씩 재시도한다: rows={}", rows.size(), e);
            Sentry.captureException(e);
            for (SnapshotRow row : rows) {
                try {
                    batchRepository.bulkUpsert(List.of(row));
                } catch (Exception rowEx) {
                    log.error("스냅샷 개별 flush 실패 — 건너뛴다: userId={}, startedAt={}", row.userId(), row.startedAt(), rowEx);
                    Sentry.captureException(rowEx);
                }
            }
        }
    }
}
