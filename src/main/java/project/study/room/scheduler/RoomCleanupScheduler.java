package project.study.room.scheduler;

import static project.study.room.service.RoomService.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.room.service.RoomService;

@Component
@RequiredArgsConstructor
public class RoomCleanupScheduler {

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        List<AutoLeave> removed = roomService.cleanupExpired(Instant.now());
        for (AutoLeave al : removed) {
            messagingTemplate.convertAndSend(
                    "/topic/room/" + al.roomId(), (Object) Map.of("type", "MEMBER_LEFT", "userId", al.userId()));
        }
    }
}
