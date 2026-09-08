package project.study.room.scheduler;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;

/**
 * 만료 스윕 진행자 — 5초마다 정리 서비스를 돌리고 자리마다 MEMBER_LEFT를 알린다.
 *
 * <p>기본은 켜짐이고 {@code app.room.cleanup.enabled=false}로 끌 수 있다. 통합 테스트에서만 끈다 —
 * 백그라운드 스윕이 테스트가 커밋한 방·리스 행을 건드려 생기는 경합을 막기 위해서다
 * (정리 로직 자체는 RoomCleanupService를 직접 불러 검증한다).
 */
@Component
@ConditionalOnProperty(name = "app.room.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RoomCleanupScheduler {

    private final RoomCleanupService roomCleanupService;
    private final SimpMessagingTemplate messagingTemplate;

    // 방 하나를 커밋할 때마다 그 방의 MEMBER_LEFT를 즉시 보낸다 — 뒤 방의 실패가 앞 방의 알림을 막지 않는다
    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        roomCleanupService.cleanupExpired(Instant.now(), this::broadcastLeft);
    }

    // 발송 실패를 자리마다 가둔다 — 한 건이 터져도 같은 방의 남은 MEMBER_LEFT가 함께 사라지지 않는다
    private void broadcastLeft(AutoLeave autoLeave) {
        try {
            messagingTemplate.convertAndSend("/topic/room/" + autoLeave.roomId(), (Object)
                    Map.of("type", "MEMBER_LEFT", "userId", autoLeave.userId()));
        } catch (RuntimeException e) {
            log.warn("MEMBER_LEFT 발송 실패: roomId={}, userId={}", autoLeave.roomId(), autoLeave.userId(), e);
        }
    }
}
