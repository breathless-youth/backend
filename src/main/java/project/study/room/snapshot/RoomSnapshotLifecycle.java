package project.study.room.snapshot;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import project.study.room.service.RoomService;
import project.study.room.service.RoomStateSnapshot;
import tools.jackson.databind.ObjectMapper;

/**
 * 종료 훅 — 소켓이 닫히기 전에 룸 메모리를 스냅샷으로 저장한다 (BY-626).
 *
 * <p>{@link SmartLifecycle}는 phase가 큰 것부터 멈춘다. {@code Integer.MAX_VALUE}로 두어 Boot의 웹서버
 * 정지보다 먼저 실행되게 한다 (Boot 4.1 실측: WebServerGracefulShutdownLifecycle = MAX-1024,
 * WebServerStartStopLifecycle = MAX-2048). 아직 아무도 끊기지 않은 완전한 상태를 담고, 클라이언트가
 * 새 태스크로 재연결하기 전에 스냅샷이 DB에 있게 된다. 저장 실패는 로그만 남긴다 — 종료를 막을 이유가 없다.
 */
@Component
@RequiredArgsConstructor
public class RoomSnapshotLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RoomSnapshotLifecycle.class);

    private final RoomService roomService;
    private final RoomStateSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        try {
            RoomStateSnapshot snapshot = roomService.exportSnapshot(clock.instant());
            // 방이 없어도 초대코드 묘비(10분)가 남아 있으면 저장한다 — 배포를 넘겨도 "방이 종료되었어요"로 답하기 위해
            if (snapshot.rooms().isEmpty() && snapshot.closedCodes().isEmpty()) {
                repository.clear();
                log.info("룸 스냅샷 생략(방·묘비 없음) — 기존 스냅샷을 지웠다");
                return;
            }
            repository.save(objectMapper.writeValueAsString(snapshot), snapshot.takenAt());
            int participants = snapshot.rooms().stream()
                    .mapToInt(r -> r.participants().size())
                    .sum();
            log.info("룸 스냅샷 저장: 방 {}개, 참가자 {}명", snapshot.rooms().size(), participants);
        } catch (RuntimeException e) {
            log.error("룸 스냅샷 저장 실패 — 이번 배포에서 방은 이어지지 않는다", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
