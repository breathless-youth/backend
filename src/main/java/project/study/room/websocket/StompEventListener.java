package project.study.room.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;

@Component
@RequiredArgsConstructor
public class StompEventListener {

    private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) return;

        Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) return;

        Principal principal = accessor.getUser();
        if (principal == null) return;

        Long roomId = Long.valueOf(matcher.group(1));
        Long userId = Long.valueOf(principal.getName());
        String sessionId = accessor.getSessionId();

        List<RoomMember> members = roomService.confirmStomp(roomId, userId, sessionId);
        if (members.isEmpty()) return;

        messagingTemplate.convertAndSendToUser(
                principal.getName(), "/queue/room", Map.of("type", "SNAPSHOT", "members", members));

        RoomMember self = members.stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElse(new RoomMember(userId, false, "FOCUS"));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_JOINED", "member", self));
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        roomService.handleDisconnect(sessionId);
    }
}
