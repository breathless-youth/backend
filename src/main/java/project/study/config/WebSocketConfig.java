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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import project.study.common.logging.StompMdcChannelInterceptor;
import project.study.room.service.RoomService;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.SessionTrackingDecorator;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String USER_ID_ATTR = "userId";
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final RoomService roomService;
    private final SessionRegistry sessionRegistry;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 미사용 — FE가 @stomp/stompjs로 순수 WebSocket에 직접 접속한다
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").addInterceptors(new UserIdHandshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic, /queue는 메세지 브로커가 관리
        registry.enableSimpleBroker("/topic", "/queue")
                // [0]은 서버가 클라이언트에게 보내는 주기, [1]은 클라이언트가 서버에게 보내는 주기 - 클라이언트도 CONNECT시에 보내는데 더 큰 값으로 합의
                // 클라이언트가 0으로 보내면 HeartBeat는 비활성화
                .setHeartbeatValue(new long[] {HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
                .setTaskScheduler(heartbeatTaskScheduler());
        // /app은 브로커가 아니라 controller로 보내는 기능
        registry.setApplicationDestinationPrefixes("/app");
        // /user는 특정 사용자에게멘 보내는 기능
        registry.setUserDestinationPrefix("/user");
    }

    private TaskScheduler heartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        // 빈으로 등록하지 않으면 스스로 초기화해줘야한다
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 인가 인터셉터가 CONNECT에서 프린시펄을 세팅하므로 MDC 인터셉터는 그 뒤에 둔다
        registration.interceptors(new UserIdChannelInterceptor(roomService), new StompMdcChannelInterceptor());
    }

    // WS 전송 한도 (BY-491). 기본값(메시지 64KB, 세션당 송신버퍼 512KB)은 우리 메시지
    // 실측(state ~200B, 시그널 ~300B, SNAPSHOT ~2KB) 대비 수십~수백 배 과하다.
    // 송신버퍼는 CPU 포화로 브로드캐스트 flush가 밀릴 때 세션마다 쌓이는 곳이라,
    // 한도 초과한 느린/죽은 클라이언트는 세션이 종료된다(좀비 커넥션 정리 효과).
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(16 * 1024); // 최대 메시지(SNAPSHOT ~2KB)의 8배 여유
        registry.setSendBufferSizeLimit(64 * 1024); // 세션당 송신 대기 상한 512KB → 64KB
        registry.setSendTimeLimit(5_000); // 5초 내 못 보내는 세션은 정리

        // 소켓 핸들을 레지스트리에 등록한다 — confirm 사전/사후 검사와 펜싱(전체 닫기)에 쓴다 (BY-626)
        registry.addDecoratorFactory(handler -> new SessionTrackingDecorator(handler, sessionRegistry));
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
                    // 핸드셰이크 이후에는 HTTP가 끝나므로 세션 저장소에 userId 저장
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

    // STOMP 보안
    @RequiredArgsConstructor
    static class UserIdChannelInterceptor implements ChannelInterceptor {

        // 정규식을 미리 컴파일해서 static으로 보관
        private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

        private final RoomService roomService;

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            // Message는 Spring 메시징의 범용 타입이라, STOMP 메세지 안전하게 꺼내기
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor == null) return message;

            StompCommand command = accessor.getCommand();
            if (command == null) return message; // heartbeat 등 커맨드 없는 프레임

            return switch (command) {
                // CONNECT 대신 STOMP 커맨드도 유효한 연결 프레임
                case CONNECT, STOMP -> {
                    // Principal 세팅
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
        // 핸들러의 인가·검증을 우회한 위조 이벤트 주입을 막는다
        private static boolean allowSend(StompHeaderAccessor accessor) {
            String destination = accessor.getDestination();
            return destination != null && destination.startsWith("/app/");
        }

        // SUBSCRIBE 인가 — deny-by-default. simple broker는 Ant 패턴 구독(/topic/room/** 등)을
        // 지원하므로 정확히 일치하는 두 목적지만 허용한다. false = 메시지 드랍 → 구독 미생성.
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
