package project.study.room.snapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import project.study.room.service.RoomService;
import project.study.room.service.RoomStateSnapshot;
import tools.jackson.databind.ObjectMapper;

/**
 * 새 태스크가 옛 태스크의 스냅샷을 이어받는다 (BY-626).
 *
 * <p>호출 시점은 "이 태스크가 모르는 방"을 만났을 때다 — STOMP SUBSCRIBE 인가(WebSocketConfig)와 HTTP join.
 * SUBSCRIBE는 인가 인터셉터가 참가자 아님으로 프레임을 버리면 이벤트가 뜨지 않으므로 인가 직전에 건다. 배포 겹침 구간에는
 * 옛 태스크가 아직 스냅샷을 쓰기 전일 수 있으므로 "없음"은 기억하지 않고 매번 다시 본다(미스마다 SELECT 하나,
 * 미스는 오타 수준이라 무시할 양). 실제로 복원한 뒤에는 태스크당 다시 하지 않는다.
 *
 * <p>읽기(I/O)는 여기서, 적용은 {@link RoomService#importSnapshot}(락 안, 메모리)에서 — 글로벌 락 안
 * I/O 금지 규칙을 지킨다. 신선도 {@value #MAX_AGE_SECONDS}초를 넘긴 스냅샷은 무시한다: 배포가 아니라
 * 한참 뒤 재시작이면 그 방들은 이미 끝난 지 오래다.
 */
@Component
@RequiredArgsConstructor
public class RoomSnapshotRestorer {

    public static final long MAX_AGE_SECONDS = 120;

    private static final Logger log = LoggerFactory.getLogger(RoomSnapshotRestorer.class);

    private final RoomService roomService;
    private final RoomStateSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile boolean restored = false;

    /** @return 이번 호출에서 실제로 복원했으면 true — 호출자는 true일 때 원래 하려던 조회를 다시 한다 */
    public synchronized boolean restoreIfAvailable() {
        if (restored) {
            return false;
        }
        Instant now = clock.instant();
        Optional<String> payload;
        try {
            payload = repository.loadIfSavedAfter(now.minus(Duration.ofSeconds(MAX_AGE_SECONDS)));
        } catch (RuntimeException e) {
            log.error("룸 스냅샷 조회 실패 — 복원 없이 진행한다", e);
            return false;
        }
        if (payload.isEmpty()) {
            return false;
        }
        try {
            RoomStateSnapshot snapshot = objectMapper.readValue(payload.get(), RoomStateSnapshot.class);
            int rooms = roomService.importSnapshot(snapshot, now);
            restored = true;
            clearQuietly();
            log.info("룸 스냅샷 이어받기 완료: 방 {}개", rooms);
            return true;
        } catch (RuntimeException e) {
            // 깨진 스냅샷이 매 요청을 막으면 안 된다 — 지우고 복원 없이 진행
            log.error("룸 스냅샷 복원 실패 — 스냅샷을 폐기한다", e);
            clearQuietly();
            return false;
        }
    }

    // 정리는 best-effort — DB가 흔들려 삭제가 실패해도 재접속(인가·join) 경로로 예외가 새면 안 된다.
    // 남은 스냅샷은 신선도(MAX_AGE)가 지나면 자연히 무시된다
    private void clearQuietly() {
        try {
            repository.clear();
        } catch (RuntimeException e) {
            log.warn("룸 스냅샷 삭제 실패 — 신선도 만료로 무시될 때까지 남겨둔다", e);
        }
    }
}
