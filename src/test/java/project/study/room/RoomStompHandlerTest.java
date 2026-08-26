package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;
import project.study.room.websocket.RoomStompHandler;

@ExtendWith(MockitoExtension.class)
class RoomStompHandlerTest {

    private static final Long ROOM_ID = 10L;
    private static final Principal USER_1 = () -> "1";

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private RoomStompHandler handler;

    private static SimpMessageHeaderAccessor accessorWithSession(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        return accessor;
    }

    @Test
    void 활성_세션_멤버의_스냅샷_재요청은_요청_세션의_개인_큐로만_SNAPSHOT을_보낸다() {
        List<RoomMember> members = List.of(
                new RoomMember(1L, "닉네임", "목표", "수능", true, "FOCUS", 120),
                new RoomMember(2L, "친구", "목표2", "수능", false, "DISTRACTED", 45));
        when(roomService.getMembersForActiveSession(ROOM_ID, 1L, "session-1")).thenReturn(members);

        handler.handleSnapshotRequest(ROOM_ID, USER_1, accessorWithSession("session-1"));

        // 같은 유저의 남은 옛 세션이 아니라 요청 세션에만 배달되도록 세션 스코프 헤더를 확인한다
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq("1"),
                        eq("/queue/room"),
                        eq(Map.of("type", "SNAPSHOT", "members", members)),
                        headers.capture());
        assertThat(headers.getValue()).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, "session-1");
        // 다른 멤버에게는 어떤 브로드캐스트도 없다
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void 스냅샷_재요청은_방_상태를_변경하지_않는다() {
        when(roomService.getMembersForActiveSession(ROOM_ID, 1L, "session-1"))
                .thenReturn(List.of(new RoomMember(1L, "닉네임", null, null, false, "FOCUS", 0)));

        handler.handleSnapshotRequest(ROOM_ID, USER_1, accessorWithSession("session-1"));
        handler.handleSnapshotRequest(ROOM_ID, USER_1, accessorWithSession("session-1"));

        // 멱등 — 몇 번을 받아도 원자 조회 외의 호출(상태 변경)이 없다
        verify(roomService, times(2)).getMembersForActiveSession(ROOM_ID, 1L, "session-1");
        verifyNoMoreInteractions(roomService);
    }

    @Test
    void 비멤버나_옛_세션의_스냅샷_재요청은_조용히_무시한다() {
        when(roomService.getMembersForActiveSession(ROOM_ID, 1L, "session-1")).thenReturn(List.of());

        handler.handleSnapshotRequest(ROOM_ID, USER_1, accessorWithSession("session-1"));

        // 에러 프레임도, 개인 큐 발송도 없다 — 클라 재시도가 조용히 소진되게
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void principal이_없으면_스냅샷_재요청을_무시한다() {
        handler.handleSnapshotRequest(ROOM_ID, null, accessorWithSession("session-1"));

        verifyNoInteractions(roomService, messagingTemplate);
    }
}
