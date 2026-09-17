package project.study.room.websocket;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.dto.RoomMember;
import project.study.room.lease.TaskIdentity;
import project.study.room.service.RoomService;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompEventListener {

    private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

    private final RoomService roomService;
    private final RoomMessenger messenger;
    private final SessionRegistry sessionRegistry;
    private final TaskIdentity taskIdentity;

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug("STOMP CONNECT 수신: sessionId={}", accessor.getSessionId());
    }

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        log.debug(
                "STOMP CONNECTED: sessionId={}, userId={}",
                accessor.getSessionId(),
                principal == null ? null : principal.getName());
    }

    /**
     * 방 토픽 구독 = 자리 확정 (스펙 §2.5). 옛 세션의 뒤늦은 SUBSCRIBE가 죽은 세션을 다시 등록하지 않도록
     * 레지스트리 사전 검사 → 단조 조건부 확정 → 사후 보정 세 겹으로 막는다.
     */
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
        if (!sessionRegistry.isOpen(sessionId)) {
            log.debug("닫힌 세션의 구독 무시: roomId={}, userId={}, sessionId={}", roomId, userId, sessionId);
            return;
        }
        // 레지스트리에 오픈 시각이 없으면 이미 닫혔거나 이 태스크가 모르는 세션이다. "지금"으로 대신하면
        // session_opened_at <= :openedAt이 통과해 옛 세션의 뒤늦은 SUBSCRIBE가 새 세션을 덮는다 (스펙 §2.5-2)
        Optional<Instant> openedAt = sessionRegistry.openedAt(sessionId);
        if (openedAt.isEmpty()) {
            log.debug("레지스트리에 없는 세션의 구독 무시: roomId={}, userId={}, sessionId={}", roomId, userId, sessionId);
            return;
        }

        List<RoomMember> members =
                roomService.confirmStomp(roomId, userId, sessionId, openedAt.get(), taskIdentity.id());
        if (members.isEmpty()) {
            // 인가는 통과했는데 그 사이 자리가 회수됐거나 옛 세션 — FE가 join을 다시 부르게 알린다
            messenger.roomUnavailable(principal.getName(), sessionId, roomId);
            return;
        }
        if (!sessionRegistry.isOpen(sessionId)) {
            roomService.handleDisconnect(sessionId); // 확정 중에 닫힘(펜싱 등) — 끊김으로 되돌린다
            return;
        }
        log.debug("STOMP 확정: roomId={}, userId={}, 확정 인원={}", roomId, userId, members.size());

        messenger.toSession(
                principal.getName(), sessionId, Map.of("type", RoomMessageType.SNAPSHOT.name(), "members", members));
        RoomMember self = members.stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElse(new RoomMember(userId, null, null, null, false, "FOCUS", 0, false));
        messenger.broadcast(roomId, Map.of("type", RoomMessageType.MEMBER_JOINED.name(), "member", self));
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
