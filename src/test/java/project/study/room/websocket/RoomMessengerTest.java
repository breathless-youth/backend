package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RoomMessengerTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final RoomMessenger messenger = new RoomMessenger(template);

    @Test
    void 세션_스코프_발송은_요청_세션_헤더를_단다() {
        messenger.roomUnavailable("7", "session-1", 3L);

        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(template)
                .convertAndSendToUser(
                        eq("7"),
                        eq("/queue/room"),
                        eq(Map.of("type", "ROOM_UNAVAILABLE", "roomId", 3L)),
                        headers.capture());
        assertThat(headers.getValue()).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, "session-1");
    }

    @Test
    void 브로드캐스트는_방_토픽으로_간다() {
        Map<String, Object> payload = Map.of("type", "MEMBER_LEFT", "userId", 7L);

        messenger.broadcast(3L, payload);

        verify(template).convertAndSend("/topic/room/3", (Object) payload);
    }
}
