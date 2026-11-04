package project.study.group.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/**
 * 실제 소켓 없이 STOMP 구독(= 자리 확정)을 흉내 낸다 (BY-1124). 세션을 레지스트리에 열어 두고 {@link StompEventListener}에 SUBSCRIBE
 * 이벤트를 그대로 넘긴다 — 확정·SNAPSHOT·MEMBER_JOINED가 운영과 같은 경로로 나간다. 공유 컨텍스트의 레지스트리를 쓰므로 {@link #close}로 연
 * 세션을 지운다.
 */
public final class RoomSessions implements AutoCloseable {

    private final SessionRegistry registry;
    private final StompEventListener listener;
    private final List<String> opened = new ArrayList<>();

    public RoomSessions(SessionRegistry registry, StompEventListener listener) {
        this.registry = registry;
        this.listener = listener;
    }

    /** 새 세션을 열고 방 토픽을 구독한다. 그 세션 ID를 돌려준다. */
    public String subscribe(long userId, long roomId) {
        String sessionId = "s-" + UUID.randomUUID();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        registry.register(socket);
        opened.add(sessionId);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination("/topic/room/" + roomId);
        accessor.setUser(principal(userId));
        listener.handleSubscribe(new SessionSubscribeEvent(
                this, MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders())));
        return sessionId;
    }

    public static Principal principal(long userId) {
        return () -> String.valueOf(userId);
    }

    /** 발행 핸들러에 넘길 헤더 — 세션 ID만 있으면 된다. */
    public static SimpMessageHeaderAccessor accessor(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        return accessor;
    }

    @Override
    public void close() {
        opened.forEach(registry::remove);
    }
}
