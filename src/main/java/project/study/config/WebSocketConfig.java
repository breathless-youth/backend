package project.study.config;

import io.jsonwebtoken.JwtException;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import project.study.common.logging.StompMdcChannelInterceptor;
import project.study.room.service.RoomStateService;
import project.study.room.websocket.RoomMessenger;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.SessionTrackingDecorator;
import project.study.user.jwt.JwtUtil;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final RoomStateService roomStateService;
    private final SessionRegistry sessionRegistry;
    // SimpMessagingTemplate(을 필요로 하는 RoomMessenger)을 configurer에 직접 주입하면 브로커 구성과
    // 순환 참조가 나므로 ObjectProvider로 지연 해석한다
    private final ObjectProvider<RoomMessenger> roomMessenger;
    private final JwtUtil jwtUtil;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 미사용 — FE가 @stomp/stompjs로 순수 WebSocket에 직접 접속한다.
        // 핸드셰이크에서는 아무것도 식별하지 않는다 — 인증은 CONNECT 프레임의 Authorization 헤더(아래 인터셉터)
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
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
        // 인바운드 핸들러가 이제 JDBC로 블로킹된다 — 기본(코어×2, 0.5vCPU에서 2개)은 부족하다 (BY-626, 스펙 §7)
        registration.taskExecutor().corePoolSize(8).maxPoolSize(8);
        // 인가 인터셉터가 CONNECT에서 프린시펄을 세팅하므로 MDC 인터셉터는 그 뒤에 둔다
        registration.interceptors(
                new UserIdChannelInterceptor(roomStateService, roomMessenger, jwtUtil),
                new StompMdcChannelInterceptor());
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

    // STOMP 보안
    @RequiredArgsConstructor
    static class UserIdChannelInterceptor implements ChannelInterceptor {

        // 정규식을 미리 컴파일해서 static으로 보관
        private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");
        private static final String BEARER_PREFIX = "Bearer ";

        private final RoomStateService roomStateService;
        private final ObjectProvider<RoomMessenger> messenger;
        private final JwtUtil jwtUtil;

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
                    // 실패는 예외로 거부한다 — null 반환은 아무 프레임도 안 나가 클라이언트가 CONNECTED를 무한 대기한다
                    accessor.setUser(authenticate(message, accessor));
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

        /**
         * CONNECT 프레임의 {@code Authorization: Bearer <access>}로 principal을 만든다. 핸드셰이크 URL 쿼리는
         * 쓰지 않는다 — 토큰이 ALB 액세스 로그에 남기 때문이다. 검증은 CONNECT 시점 한 번이고, 접속 중 access가
         * 만료돼도 세션은 유지된다(재접속 전에 갱신해서 붙이는 것은 클라이언트 몫).
         *
         * <p>실패는 {@link MessageDeliveryException}(MessagingException 계열)로 던져야 StompSubProtocolHandler가
         * ERROR 프레임을 보내고 소켓을 닫는다 — 다른 예외는 채널이 감싸 버려 이 메시지가 사라진다.
         */
        private Principal authenticate(Message<?> message, StompHeaderAccessor accessor) {
            String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith(BEARER_PREFIX)) {
                throw new MessageDeliveryException(message, "인증이 필요합니다");
            }
            try {
                // refresh 토큰(opaque UUID)은 JWT 파싱 자체가 실패한다. 예외 메시지에 토큰을 싣지 않는다
                return new StompPrincipal(jwtUtil.getUserId(header.substring(BEARER_PREFIX.length())));
            } catch (JwtException | IllegalArgumentException e) {
                throw new MessageDeliveryException(message, "유효하지 않은 토큰입니다");
            }
        }

        // 클라이언트 SEND는 /app/** 만 허용 — 브로커 목적지(/topic, /queue)로의 직접 발행을 차단해
        // 핸들러의 인가·검증을 우회한 위조 이벤트 주입을 막는다. principal 없는 세션의 SEND도 버린다
        private static boolean allowSend(StompHeaderAccessor accessor) {
            String destination = accessor.getDestination();
            return accessor.getUser() != null && destination != null && destination.startsWith("/app/");
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
            boolean allowed = roomStateService.hasParticipant(roomId, userId);
            // 프레임은 버리되 요청 세션에만 알린다 — 지금까지는 조용히 버려져 FE가 거부를 알 길이 없었다.
            // sessionId가 없으면 보내지 않는다 — 세션 없는 발송은 유저 스코프가 되어 그 유저의 다른 세션까지 팬아웃한다
            if (!allowed && accessor.getSessionId() != null) {
                messenger.getObject().roomUnavailable(principal.getName(), accessor.getSessionId(), roomId);
            }
            return allowed;
        }
    }
}
