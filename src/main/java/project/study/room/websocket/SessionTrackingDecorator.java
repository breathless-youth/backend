package project.study.room.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** 소켓이 열리고 닫힐 때 SessionRegistry에 핸들을 등록·해제한다. 세션 ID는 STOMP simpSessionId와 같다. */
public class SessionTrackingDecorator extends WebSocketHandlerDecorator {

    private final SessionRegistry registry;

    public SessionTrackingDecorator(WebSocketHandler delegate, SessionRegistry registry) {
        super(delegate);
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        registry.register(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        registry.remove(session.getId());
        super.afterConnectionClosed(session, closeStatus);
    }
}
