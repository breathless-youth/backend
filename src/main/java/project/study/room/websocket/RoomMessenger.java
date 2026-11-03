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

    /**
     * 그 사용자의 모든 세션 개인 큐로 보낸다 — 세션 헤더를 달지 않는다. 방 밖에서 일어난 일(내보내짐·차단)은 어느 세션이 방을 보고 있는지 가리지 않고
     * 알려야 해서다 (BY-1124 KICKED·PEER_BLOCKED).
     */
    public void toUser(long userId, Object payload) {
        messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/room", payload);
    }

    /**
     * 차단한 사이인 상대({@code peerId})를 그 사람의 모든 세션에 알린다 (BY-1124 PEER_BLOCKED). 받는 사람이 차단한 쪽이든 당한 쪽이든 모양이
     * 같아({@code type}과 상대 {@code userId}뿐) 누가 차단했는지 드러나지 않는다 — 모양은 이 클래스 한 곳에서만 만든다.
     */
    public void peerBlocked(long userId, long peerId) {
        toUser(userId, peerBlockedMessage(peerId));
    }

    /** 같은 PEER_BLOCKED를 SNAPSHOT을 받은 그 세션에만 보낸다 — SNAPSHOT 바로 뒤에 다시 실어 줄 때 쓴다. */
    public void peerBlocked(String userName, String sessionId, long peerId) {
        toSession(userName, sessionId, peerBlockedMessage(peerId));
    }

    private static Map<String, Object> peerBlockedMessage(long peerId) {
        return Map.of("type", RoomMessageType.PEER_BLOCKED.name(), "userId", peerId);
    }

    public void broadcast(Long roomId, Object payload) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId, payload);
    }

    /** 구독 거부·확정 실패 — FE는 join을 다시 부르거나 종료 안내를 띄운다. 배달은 보장되지 않는다(스펙 §2.9). */
    public void roomUnavailable(String userName, String sessionId, Long roomId) {
        toSession(userName, sessionId, Map.of("type", RoomMessageType.ROOM_UNAVAILABLE.name(), "roomId", roomId));
    }
}
