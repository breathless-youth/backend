package project.study.room.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.service.RoomService.LeaveResult;
import project.study.room.websocket.RoomMessageType;

/**
 * 사용자가 지금 있는 방에서 내보낸다. 병합·탈퇴 직전에 쓴다. 방이 아직 열려 있으면 {@code RoomController.leave}와 같은 MEMBER_LEFT를 보낸다.
 * 자체 트랜잭션이 없으므로 트랜잭션 밖에서 불러야 한다(나가기는 {@link RoomService#leave}의 트랜잭션).
 */
@Component
@RequiredArgsConstructor
public class CurrentRoomLeaver {

    private final RoomParticipationRepository participations;
    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    public void leave(long userId) {
        participations.findLiveRoomIdOfUser(userId).ifPresent(roomId -> {
            LeaveResult result = roomService.leave(roomId, userId);
            if (result.removed() && result.roomStillOpen()) {
                messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                        Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", userId));
            }
        });
    }
}
