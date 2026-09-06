package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import project.study.room.service.RoomService;
import project.study.room.snapshot.RoomSnapshotRestorer;

/** SUBSCRIBE 인가와 배포 직후 스냅샷 복원 트리거 (BY-626) — 인가가 프레임을 버리기 전에 복원이 선행돼야 한다. */
@ExtendWith(MockitoExtension.class)
class UserIdChannelInterceptorTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomSnapshotRestorer snapshotRestorer;

    @Mock
    private MessageChannel channel;

    private static Message<byte[]> subscribe(String destination, Long userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (userId != null) {
            Principal principal = () -> String.valueOf(userId);
            accessor.setUser(principal);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private WebSocketConfig.UserIdChannelInterceptor interceptor() {
        return new WebSocketConfig.UserIdChannelInterceptor(roomService, snapshotRestorer);
    }

    @Test
    void 모르는_방_구독은_인가_판정_전에_스냅샷_복원을_시도한다() {
        when(roomService.roomExists(7L)).thenReturn(false);
        when(roomService.hasParticipant(7L, 100L)).thenReturn(true); // 복원으로 자리가 생긴 상황

        Message<?> result = interceptor().preSend(subscribe("/topic/room/7", 100L), channel);

        assertThat(result).isNotNull();
        var order = inOrder(snapshotRestorer, roomService);
        order.verify(snapshotRestorer).restoreIfAvailable();
        order.verify(roomService).hasParticipant(7L, 100L);
    }

    @Test
    void 복원해도_자리가_없으면_여전히_구독을_거부한다() {
        when(roomService.roomExists(7L)).thenReturn(false);
        when(roomService.hasParticipant(7L, 100L)).thenReturn(false);

        assertThat(interceptor().preSend(subscribe("/topic/room/7", 100L), channel))
                .isNull();
        verify(snapshotRestorer).restoreIfAvailable();
    }

    @Test
    void 아는_방_구독은_스냅샷을_들여다보지_않는다() {
        when(roomService.roomExists(7L)).thenReturn(true);
        when(roomService.hasParticipant(7L, 100L)).thenReturn(true);

        assertThat(interceptor().preSend(subscribe("/topic/room/7", 100L), channel))
                .isNotNull();
        verify(snapshotRestorer, never()).restoreIfAvailable();
    }

    @Test
    void 개인_큐_구독은_방과_무관하게_허용되고_복원을_건드리지_않는다() {
        assertThat(interceptor().preSend(subscribe("/user/queue/room", 100L), channel))
                .isNotNull();
        verify(snapshotRestorer, never()).restoreIfAvailable();
    }

    @Test
    void 프린시펄_없는_방_구독은_거부한다() {
        assertThat(interceptor().preSend(subscribe("/topic/room/7", null), channel))
                .isNull();
        verify(snapshotRestorer, never()).restoreIfAvailable();
    }
}
