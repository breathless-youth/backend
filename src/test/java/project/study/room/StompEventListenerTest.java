package project.study.room;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import project.study.room.service.RoomService;
import project.study.room.websocket.StompEventListener;

@ExtendWith(MockitoExtension.class)
class StompEventListenerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private StompEventListener listener;

    private static Message<byte[]> stompMessage(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // CONNECT/CONNECTED는 로그만 남기고 룸 상태에는 관여하지 않는다 — 부수효과 없음을 고정한다
    @Test
    void CONNECT_수신은_예외_없이_처리되고_룸_상태에_관여하지_않는다() {
        SessionConnectEvent event = new SessionConnectEvent(this, stompMessage(StompCommand.CONNECT, "session-1"));

        assertThatCode(() -> listener.handleConnect(event)).doesNotThrowAnyException();
        verifyNoInteractions(roomService, messagingTemplate);
    }

    @Test
    void CONNECTED_수신은_예외_없이_처리되고_룸_상태에_관여하지_않는다() {
        SessionConnectedEvent event =
                new SessionConnectedEvent(this, stompMessage(StompCommand.CONNECTED, "session-1"));

        assertThatCode(() -> listener.handleConnected(event)).doesNotThrowAnyException();
        verifyNoInteractions(roomService, messagingTemplate);
    }
}
