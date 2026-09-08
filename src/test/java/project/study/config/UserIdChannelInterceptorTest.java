package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import project.study.room.service.RoomStateService;
import project.study.room.websocket.RoomMessenger;

class UserIdChannelInterceptorTest {

    private final RoomStateService roomState = mock(RoomStateService.class);
    private final RoomMessenger messenger = mock(RoomMessenger.class);

    @SuppressWarnings("unchecked")
    private WebSocketConfig.UserIdChannelInterceptor interceptor() {
        ObjectProvider<RoomMessenger> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messenger);
        return new WebSocketConfig.UserIdChannelInterceptor(roomState, provider);
    }

    private static Message<byte[]> subscribe(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setDestination(destination);
        if (userId != null) accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
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
