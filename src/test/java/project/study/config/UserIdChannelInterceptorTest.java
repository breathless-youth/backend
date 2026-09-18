package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.room.service.RoomStateService;
import project.study.room.websocket.RoomMessenger;
import project.study.user.jwt.JwtUtil;
import project.study.user.service.UserService;

class UserIdChannelInterceptorTest {

    private final RoomStateService roomState = mock(RoomStateService.class);
    private final RoomMessenger messenger = mock(RoomMessenger.class);
    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-at-least-32-chars-long", 3_600_000L);
    // 구 앱 폴백의 실존 유저 확인용 — 스텁하지 않은 userId는 존재하는 것으로 본다(null 반환), 없는 유저만 예외를 던지게 한다
    private final UserService userService = mock(UserService.class);

    @SuppressWarnings("unchecked")
    private WebSocketConfig.UserIdChannelInterceptor interceptor() {
        ObjectProvider<RoomMessenger> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messenger);
        return new WebSocketConfig.UserIdChannelInterceptor(roomState, provider, jwtUtil, userService);
    }

    private static Message<byte[]> subscribe(String destination, String userId) {
        return subscribe(destination, userId, "s1");
    }

    private static Message<byte[]> subscribe(String destination, String userId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        if (sessionId != null) accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        if (userId != null) accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 운영에서 CONNECT의 accessor는 가변이다(Spring이 커스텀 인터셉터 뒤에 immutable 처리를 붙인다) — 같은 조건을 만든다. */
    private static Message<byte[]> connect(StompCommand command, String authorization) {
        return connect(command, authorization, null);
    }

    /** 구 앱은 핸드셰이크 {@code ?userId=}가 세션 속성에 실려 온다 (ADR-0020). */
    private static Message<byte[]> connect(StompCommand command, String authorization, String handshakeUserId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        if (authorization != null) accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, authorization);
        if (handshakeUserId != null) {
            accessor.setSessionAttributes(new HashMap<>(Map.of("userId", handshakeUserId)));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> send(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("s1");
        accessor.setDestination(destination);
        if (userId != null) accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 유효한_Bearer_access로_CONNECT하면_principal이_userId가_된다() {
        Message<?> result = interceptor()
                .preSend(
                        connect(StompCommand.CONNECT, "Bearer " + jwtUtil.createAccessToken(7L)),
                        mock(MessageChannel.class));

        assertThat(SimpMessageHeaderAccessor.getUser(result.getHeaders()).getName())
                .isEqualTo("7");
    }

    @Test
    void STOMP_커맨드도_CONNECT와_같은_규칙으로_인증한다() {
        Message<?> result = interceptor()
                .preSend(
                        connect(StompCommand.STOMP, "Bearer " + jwtUtil.createAccessToken(8L)),
                        mock(MessageChannel.class));

        assertThat(SimpMessageHeaderAccessor.getUser(result.getHeaders()).getName())
                .isEqualTo("8");
    }

    @Test
    void Authorization_없이_핸드셰이크_userId가_있으면_구_앱으로_보고_그_값이_principal이_된다() {
        Message<?> result = interceptor().preSend(connect(StompCommand.CONNECT, null, "9"), mock(MessageChannel.class));

        assertThat(SimpMessageHeaderAccessor.getUser(result.getHeaders()).getName())
                .isEqualTo("9");
    }

    @Test
    void Authorization이_있으면_핸드셰이크_userId는_무시된다() {
        Message<?> result = interceptor()
                .preSend(
                        connect(StompCommand.CONNECT, "Bearer " + jwtUtil.createAccessToken(7L), "9"),
                        mock(MessageChannel.class));

        assertThat(SimpMessageHeaderAccessor.getUser(result.getHeaders()).getName())
                .isEqualTo("7");
    }

    @Test
    void 핸드셰이크_userId가_존재하지_않는_유저면_거부된다() {
        // 토큰과 달리 아무 숫자나 올 수 있다 — 계정 없는 principal이 구독을 쌓지 못하게 한다 (Codex 보안 챌린지)
        doThrow(new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"))
                .when(userService)
                .getProfile(404L);

        assertThatThrownBy(() ->
                        interceptor().preSend(connect(StompCommand.CONNECT, null, "404"), mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("인증이 필요합니다");
    }

    @Test
    void 핸드셰이크_userId가_양의_Long이_아니면_거부된다() {
        MessageChannel channel = mock(MessageChannel.class);
        // allowSubscribe가 Long.valueOf로 읽는다 — 문자열·범위 초과·0 이하는 CONNECT에서 끊는다
        for (String bad : new String[] {"me", "99999999999999999999", "0", "-1"}) {
            assertThatThrownBy(() -> interceptor().preSend(connect(StompCommand.CONNECT, null, bad), channel))
                    .as("userId=" + bad)
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("인증이 필요합니다");
        }
    }

    @Test
    void Authorization_없는_CONNECT는_MessagingException으로_거부된다() {
        // null 반환이 아니라 예외여야 ERROR 프레임이 나가고 소켓이 닫힌다 — 조용히 버리면 클라이언트가 무한 대기한다
        assertThatThrownBy(() -> interceptor().preSend(connect(StompCommand.CONNECT, null), mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("인증이 필요합니다");
    }

    @Test
    void 위조된_토큰이나_refresh_UUID로는_CONNECT할_수_없다() {
        MessageChannel channel = mock(MessageChannel.class);
        JwtUtil otherKey = new JwtUtil("another-secret-key-that-is-32-chars-or-more", 3_600_000L);

        assertThatThrownBy(() ->
                        interceptor().preSend(connect(StompCommand.CONNECT, "Bearer " + UUID.randomUUID()), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("유효하지 않은 토큰입니다");
        assertThatThrownBy(() -> interceptor()
                        .preSend(connect(StompCommand.CONNECT, "Bearer " + otherKey.createAccessToken(7L)), channel))
                .isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> interceptor().preSend(connect(StompCommand.CONNECT, "Basic abc"), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void SEND는_principal이_있고_app_목적지일_때만_통과한다() {
        WebSocketConfig.UserIdChannelInterceptor interceptor = interceptor();
        MessageChannel channel = mock(MessageChannel.class);

        assertThat(interceptor.preSend(send("/app/room/3/signal", "7"), channel))
                .isNotNull();
        assertThat(interceptor.preSend(send("/app/room/3/signal", null), channel))
                .isNull();
        assertThat(interceptor.preSend(send("/topic/room/3", "7"), channel)).isNull();
    }

    @Test
    void 예약자의_방_토픽_구독은_통과한다() {
        when(roomState.hasParticipant(3L, 7L)).thenReturn(true);
        Message<byte[]> message = subscribe("/topic/room/3", "7");

        assertThat(interceptor().preSend(message, mock(MessageChannel.class))).isSameAs(message);
        verifyNoInteractions(messenger);
    }

    @Test
    void 비예약자의_구독은_버리고_요청_세션에_ROOM_UNAVAILABLE을_보낸다() {
        when(roomState.hasParticipant(3L, 7L)).thenReturn(false);

        assertThat(interceptor().preSend(subscribe("/topic/room/3", "7"), mock(MessageChannel.class)))
                .isNull();
        verify(messenger).roomUnavailable("7", "s1", 3L);
    }

    @Test
    void 세션_ID가_없으면_거부해도_ROOM_UNAVAILABLE을_보내지_않는다() {
        when(roomState.hasParticipant(3L, 7L)).thenReturn(false);

        // 세션 없는 발송은 유저 스코프가 되어 그 유저의 다른 세션까지 팬아웃한다 — 아예 보내지 않는다
        assertThat(interceptor().preSend(subscribe("/topic/room/3", "7", null), mock(MessageChannel.class)))
                .isNull();
        verifyNoInteractions(messenger);
    }

    @Test
    void 개인_큐_구독은_항상_통과하고_다른_목적지는_버린다() {
        WebSocketConfig.UserIdChannelInterceptor interceptor = interceptor();
        MessageChannel channel = mock(MessageChannel.class);

        assertThat(interceptor.preSend(subscribe("/user/queue/room", "7"), channel))
                .isNotNull();
        assertThat(interceptor.preSend(subscribe("/topic/room/**", "7"), channel))
                .isNull();
        assertThat(interceptor.preSend(subscribe("/topic/room/3", null), channel))
                .isNull();
        verifyNoInteractions(messenger);
    }
}
