package project.study.config;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import project.study.room.service.RoomService;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String USER_ID_ATTR = "userId";

    private final RoomService roomService;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 미사용 — FE가 @stomp/stompjs로 순수 WebSocket에 직접 접속한다
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").addInterceptors(new UserIdHandshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new UserIdChannelInterceptor(roomService));
    }

    record StompPrincipal(String userId) implements Principal {
        @Override
        public String getName() {
            return userId;
        }
    }

    static class UserIdHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                String userId = servletRequest.getServletRequest().getParameter(USER_ID_ATTR);
                if (userId != null) {
                    attributes.put(USER_ID_ATTR, userId);
                }
            }
            return true;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {}
    }

    @RequiredArgsConstructor
    static class UserIdChannelInterceptor implements ChannelInterceptor {

        private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

        private final RoomService roomService;

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor == null) return message;

            StompCommand command = accessor.getCommand();
            if (command == null) return message; // heartbeat 등 커맨드 없는 프레임

            return switch (command) {
                // STOMP 1.2는 CONNECT 대신 STOMP 커맨드도 유효한 연결 프레임이다
                case CONNECT, STOMP -> {
                    setPrincipalFromSession(accessor);
                    yield message;
                }
                case SEND -> allowSend(accessor) ? message : null;
                case SUBSCRIBE -> allowSubscribe(accessor) ? message : null;
                // 서버 전용 프레임을 클라이언트가 보내면 거부 — MESSAGE 직접 발행으로 브로커에
                // 위조 이벤트를 싣는 우회를 차단한다
                case MESSAGE, CONNECTED, RECEIPT, ERROR -> null;
                // UNSUBSCRIBE, DISCONNECT, ACK, NACK, BEGIN, COMMIT, ABORT
                default -> message;
            };
        }

        private static void setPrincipalFromSession(StompHeaderAccessor accessor) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                String userId = (String) sessionAttributes.get(USER_ID_ATTR);
                if (userId != null) {
                    accessor.setUser(new StompPrincipal(userId));
                }
            }
        }

        // 클라이언트 SEND는 /app/** 만 허용 — 브로커 목적지(/topic, /queue)로의 직접 발행을 차단해
        // 핸들러의 인가·검증(isActiveSession, 화이트리스트)을 우회한 위조 이벤트 주입을 막는다
        private static boolean allowSend(StompHeaderAccessor accessor) {
            String destination = accessor.getDestination();
            return destination != null && destination.startsWith("/app/");
        }

        // SUBSCRIBE 인가 — deny-by-default. simple broker는 Ant 패턴 구독(/topic/room/** 등)을
        // 지원하므로 정확히 일치하는 두 목적지만 허용한다. false = 메시지 드랍 → 구독 미생성.
        // 알려진 한계(초안): leave 후에도 기존 구독은 살아 있다 — simple broker에 구독 강제 해제
        // API가 없어 세션 종료 장치가 필요하다. 후속 작업으로 남긴다
        private boolean allowSubscribe(StompHeaderAccessor accessor) {
            String destination = accessor.getDestination();
            if (destination == null) return false;
            if (destination.equals("/user/queue/room")) return true;

            Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
            if (!matcher.matches()) return false;

            Principal principal = accessor.getUser();
            if (principal == null) return false;

            Long roomId = Long.valueOf(matcher.group(1));
            Long userId = Long.valueOf(principal.getName());
            return roomService.hasParticipant(roomId, userId);
        }
    }
}
