package project.study.room;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.dto.RoomMember;
import project.study.room.lease.TaskIdentity;
import project.study.room.service.RoomService;
import project.study.room.websocket.RoomMessenger;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

@ExtendWith(MockitoExtension.class)
class StompEventListenerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMessenger messenger;

    @Mock
    private SessionRegistry sessionRegistry;

    @Mock
    private TaskIdentity taskIdentity;

    @InjectMocks
    private StompEventListener listener;

    private static Message<byte[]> stompMessage(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(String sessionId, String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static final RoomMember ME = new RoomMember(7L, "포메", null, null, false, "FOCUS", 0, false);

    // CONNECT/CONNECTED는 로그만 남기고 룸 상태에는 관여하지 않는다 — 부수효과 없음을 고정한다
    @Test
    void CONNECT_수신은_예외_없이_처리되고_룸_상태에_관여하지_않는다() {
        SessionConnectEvent event = new SessionConnectEvent(this, stompMessage(StompCommand.CONNECT, "session-1"));

        assertThatCode(() -> listener.handleConnect(event)).doesNotThrowAnyException();
        verifyNoInteractions(roomService, messenger);
    }

    @Test
    void CONNECTED_수신은_예외_없이_처리되고_룸_상태에_관여하지_않는다() {
        SessionConnectedEvent event =
                new SessionConnectedEvent(this, stompMessage(StompCommand.CONNECTED, "session-1"));

        assertThatCode(() -> listener.handleConnected(event)).doesNotThrowAnyException();
        verifyNoInteractions(roomService, messenger);
    }

    @Test
    void 구독_확정은_요청_세션에_SNAPSHOT을_보내고_방에_MEMBER_JOINED를_뿌린다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.of(Instant.parse("2026-09-09T00:00:00Z")));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(3L, 7L, "s1", Instant.parse("2026-09-09T00:00:00Z"), "task-A"))
                .thenReturn(List.of(ME));

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(messenger).toSession("7", "s1", Map.of("type", "SNAPSHOT", "members", List.of(ME)));
        verify(messenger).broadcast(3L, Map.of("type", "MEMBER_JOINED", "member", ME));
    }

    @Test
    void 확정에_실패하면_요청_세션에_ROOM_UNAVAILABLE을_보낸다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.of(Instant.parse("2026-09-09T00:00:00Z")));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(any(), any(), any(), any(), any())).thenReturn(List.of());

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(messenger).roomUnavailable("7", "s1", 3L);
        verifyNoMoreInteractions(messenger);
    }

    // 오픈 시각을 "지금"으로 대신하면 옛 세션의 뒤늦은 SUBSCRIBE가 단조 조건을 통과해 새 세션을 덮는다
    @Test
    void 레지스트리에_오픈_시각이_없는_세션은_확정하지_않는다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.empty());

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verifyNoInteractions(roomService, messenger);
    }

    @Test
    void 이미_닫힌_세션의_구독은_확정하지_않는다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(false);

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verifyNoInteractions(roomService, messenger);
    }

    @Test
    void 확정_중에_세션이_닫혔으면_끊김으로_보정한다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true, false);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.of(Instant.parse("2026-09-09T00:00:00Z")));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(any(), any(), any(), any(), any())).thenReturn(List.of(ME));

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(roomService).handleDisconnect("s1");
        verifyNoInteractions(messenger);
    }
}
