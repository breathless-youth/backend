package project.study.studysession.buffer;

import io.sentry.Sentry;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import project.study.studysession.buffer.SnapshotBufferStore.PendingSnapshot;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.repository.ActiveStudySessionBatchRepository;
import project.study.studysession.repository.ActiveStudySessionBatchRepository.SnapshotRow;
import tools.jackson.databind.ObjectMapper;

/**
 * 진행중 세션 스냅샷의 코얼레싱 버퍼 (BY-470).
 *
 * <p>체크포인트 주기가 짧아지면(1~3초) 요청마다 DB에 UPSERT하는 비용(파싱·직렬화·JDBC·GC)이
 * CPU와 Hikari 풀을 포화시킨다. 스냅샷은 (userId, startedAt) 단위 누적 멱등본이라, 중간 스냅샷은
 * 어차피 최신본에 덮어써진다 — 그래서 저장소에 <b>세션당 최신본만</b> 유지하고(코얼레싱),
 * {@link #flush()}에서 한 배치로 UPSERT한다. reportedAt이 큰 것만 남겨 역순 도착을 무시한다
 * (기존 UPSERT의 {@code WHERE reported_at < excluded} 가드와 같은 의미).
 *
 * <p>저장소는 {@link SnapshotBufferStore}로 추상화 — 기본은 인메모리, {@code active-session.buffer.store=redis}면
 * Redis write-behind(재기동·크래시에도 대기분 유지, 다중 태스크 대응)로 바뀐다 (BY-492).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveSnapshotBuffer {

    private final ActiveStudySessionBatchRepository batchRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SnapshotBufferStore store;

    /**
     * 스냅샷을 버퍼에 넣는다. 같은 세션의 이전 스냅샷은 덮어써지고(코얼레싱), reportedAt이 더
     * 이른(역순 도착) 스냅샷은 무시된다. 검증은 호출 측(서비스)에서 이미 끝난 상태다.
     */
    public void offer(ActiveSessionSnapshotRequest request) {
        store.offer(request, clock.instant());
    }

    /** 현재 대기 중인 세션 수 — 모니터링·테스트용. */
    public int pendingCount() {
        return store.pendingCount();
    }

    /**
     * 모아둔 최신본들을 한 배치로 UPSERT한다. drain 이후 들어온 더 최신 스냅샷은 다음 사이클에
     * 반영된다. 배치가 실패하면(예: 존재하지 않는 user_id의 FK 위반) 한 건씩 재시도해 나쁜 행만
     * 건너뛴다 — 한 poison 행이 전체 flush를 막지 않게 한다.
     */
    public void flush() {
        List<PendingSnapshot> drained = store.drainAll();
        if (drained.isEmpty()) {
            return;
        }
        List<SnapshotRow> rows = new java.util.ArrayList<>();
        for (PendingSnapshot p : drained) {
            ActiveSessionSnapshotRequest r = p.request();
            try {
                String eventsJson = objectMapper.writeValueAsString(r.events());
                rows.add(new SnapshotRow(
                        r.userId(),
                        r.startedAt(),
                        r.reportedAt(),
                        p.lastSeenAt(),
                        r.studySec(),
                        r.focusSec(),
                        eventsJson));
            } catch (Exception e) {
                // 직렬화 불가 행이 flush를 중단시키면 ack를 못 해 같은 poison 배치가 무한 재전달된다 — 건너뛴다
                log.error("스냅샷 직렬화 실패 — 건너뛴다: userId={}, startedAt={}", r.userId(), r.startedAt(), e);
                Sentry.captureException(e);
            }
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
        // 반영 시도 완료 후에만 ack — 여기 도달 전에 크래시하면 다음 drain이 같은 배치를 재전달한다
        // (UPSERT가 멱등이라 중복 무해). 개별 실패 행은 기존과 같이 드랍하고 클라 누적 재전송이 메운다.
        store.ackDrained();
    }
}
