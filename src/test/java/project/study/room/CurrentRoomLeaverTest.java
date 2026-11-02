package project.study.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.service.CurrentRoomLeaver;
import project.study.room.service.RoomService;
import project.study.room.service.RoomService.LeaveResult;
import project.study.room.websocket.RoomMessageType;

class CurrentRoomLeaverTest {

    private final RoomParticipationRepository participations = mock(RoomParticipationRepository.class);
    private final RoomService roomService = mock(RoomService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CurrentRoomLeaver leaver = new CurrentRoomLeaver(participations, roomService, messaging);

    @Test
    void 방에_있으면_나가고_방이_열려_있으면_MEMBER_LEFT를_보낸다() {
        when(participations.findLiveRoomIdOfUser(7L)).thenReturn(Optional.of(3L));
        when(roomService.leave(3L, 7L)).thenReturn(new LeaveResult(true, true));

        leaver.leave(7L);

        verify(roomService).leave(3L, 7L);
        verify(messaging).convertAndSend("/topic/room/3", (Object)
                Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", 7L));
    }

    @Test
    void 마지막_사람이라_방이_닫히면_메시지를_보내지_않는다() {
        when(participations.findLiveRoomIdOfUser(7L)).thenReturn(Optional.of(3L));
        when(roomService.leave(3L, 7L)).thenReturn(new LeaveResult(true, false));

        leaver.leave(7L);

        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void 방에_없으면_아무것도_하지_않는다() {
        when(participations.findLiveRoomIdOfUser(7L)).thenReturn(Optional.empty());

        leaver.leave(7L);

        verify(roomService, never()).leave(any(), any());
        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
