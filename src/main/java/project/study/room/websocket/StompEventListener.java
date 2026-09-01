package project.study.room.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;

@Component
@RequiredArgsConstructor
public class StompEventListener {

    private static final Logger log = LoggerFactory.getLogger(StompEventListener.class);
    private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    // CONNECT 프레임 수신 — 아직 인증 principal이 없을 수 있다(핸드셰이크 단계 이후 STOMP 레벨 연결 요청)
    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug("STOMP CONNECT 수신: sessionId={}", accessor.getSessionId());
    }

    // 서버가 CONNECTED로 응답 — 이 시점부터 세션이 붙었다고 본다
    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        log.debug(
                "STOMP CONNECTED: sessionId={}, userId={}",
                accessor.getSessionId(),
                principal == null ? null : principal.getName());
    }

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        log.debug("SUBSCRIBE 수신: sessionId={}, destination={}", accessor.getSessionId(), destination);
        if (destination == null) return;

        Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) return;

        Principal principal = accessor.getUser();
        if (principal == null) return;

        Long roomId = Long.valueOf(matcher.group(1));
        Long userId = Long.valueOf(principal.getName());
        String sessionId = accessor.getSessionId();

        List<RoomMember> members = roomService.confirmStomp(roomId, userId, sessionId);
        if (members.isEmpty()) {
            log.debug("STOMP 확정 실패(방/참가자 없음): roomId={}, userId={}", roomId, userId);
            return;
        }
        log.debug("STOMP 확정: roomId={}, userId={}, 확정 인원={}", roomId, userId, members.size());

        messagingTemplate.convertAndSendToUser(
                principal.getName(), "/queue/room", Map.of("type", "SNAPSHOT", "members", members));

        RoomMember self = members.stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElse(new RoomMember(userId, null, null, null, false, "FOCUS", 0));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_JOINED", "member", self));
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        log.debug("DISCONNECT 수신: sessionId={}, closeStatus={}", sessionId, event.getCloseStatus());
        if (sessionId == null) return;

        roomService.handleDisconnect(sessionId);
    }
}
