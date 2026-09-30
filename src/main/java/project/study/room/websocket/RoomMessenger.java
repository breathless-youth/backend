package project.study.room.websocket;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 룸 메시지 발송. 세션 스코프 발송은 같은 유저의 남은 옛 세션에 새지 않도록 요청 세션에만 배달한다. */
@Component
@RequiredArgsConstructor
public class RoomMessenger {

    private final SimpMessagingTemplate messagingTemplate;

    public void toSession(String userName, String sessionId, Object payload) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(userName, "/queue/room", payload, headers.getMessageHeaders());
    }

    public void broadcast(Long roomId, Object payload) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId, payload);
    }

    /** 구독 거부·확정 실패 — FE는 join을 다시 부르거나 종료 안내를 띄운다. 배달은 보장되지 않는다(스펙 §2.9). */
    public void roomUnavailable(String userName, String sessionId, Long roomId) {
        toSession(userName, sessionId, Map.of("type", "ROOM_UNAVAILABLE", "roomId", roomId));
    }
}
