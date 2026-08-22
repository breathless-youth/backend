package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.room.event.CloseReason;
import project.study.room.event.LeaveReason;
import project.study.room.event.ParticipantJoinedEvent;
import project.study.room.event.ParticipantLeftEvent;
import project.study.room.event.RoomClosedEvent;
import project.study.room.event.RoomCreatedEvent;
import project.study.room.service.RoomService;

class RoomServiceEventTest {

    private RoomService roomService;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        roomService = new RoomService("test-secret", 86400, List.of(), events::add);
    }

    private String createRoom() {
        return roomService.create(1L).inviteCode();
    }

    private RoomService.JoinResult join(Long userId, String code) {
        return roomService.join(userId, code, "포메" + userId, null, null);
    }

    private <T> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void 방을_만들면_RoomCreated가_발행된다() {
        roomService.create(1L);

        List<RoomCreatedEvent> created = eventsOf(RoomCreatedEvent.class);
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().createdBy()).isEqualTo(1L);
        assertThat(created.getFirst().roomUid()).isNotNull();
    }

    @Test
    void 최초_STOMP_확정_시_ParticipantJoined가_발행된다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();

        roomService.confirmStomp(roomId, 1L, "session-1");

        List<ParticipantJoinedEvent> joined = eventsOf(ParticipantJoinedEvent.class);
        assertThat(joined).hasSize(1);
        assertThat(joined.getFirst().userId()).isEqualTo(1L);
    }

    @Test
    void 재확정해도_ParticipantJoined는_다시_발행되지_않는다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");

        roomService.confirmStomp(roomId, 1L, "session-2");

        assertThat(eventsOf(ParticipantJoinedEvent.class)).hasSize(1);
    }

    @Test
    void 유예_재입장_후_재확정해도_ParticipantJoined는_발행되지_않는다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");
        roomService.handleDisconnect("session-1");

        join(1L, code);
        roomService.confirmStomp(roomId, 1L, "session-2");

        assertThat(eventsOf(ParticipantJoinedEvent.class)).hasSize(1);
        assertThat(eventsOf(ParticipantLeftEvent.class)).isEmpty();
    }

    @Test
    void 명시적_퇴장_시_ParticipantLeft_EXPLICIT이_발행된다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");

        roomService.leave(roomId, 1L);

        List<ParticipantLeftEvent> left = eventsOf(ParticipantLeftEvent.class);
        assertThat(left).hasSize(1);
        assertThat(left.getFirst().reason()).isEqualTo(LeaveReason.EXPLICIT);
    }

    @Test
    void 다른_방_입장_시_기존_방에서_SWITCHED_ROOM으로_퇴장된다() {
        String codeA = createRoom();
        long roomA = join(1L, codeA).response().roomId();
        roomService.confirmStomp(roomA, 1L, "session-1");
        String codeB = roomService.create(2L).inviteCode();

        join(1L, codeB);

        List<ParticipantLeftEvent> left = eventsOf(ParticipantLeftEvent.class);
        assertThat(left).hasSize(1);
        assertThat(left.getFirst().reason()).isEqualTo(LeaveReason.SWITCHED_ROOM);
    }

    @Test
    void 유예_만료_시_DISCONNECT_TIMEOUT으로_퇴장된다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");
        roomService.handleDisconnect("session-1");

        roomService.cleanupExpired(Instant.now().plusSeconds(31));

        List<ParticipantLeftEvent> left = eventsOf(ParticipantLeftEvent.class);
        assertThat(left).hasSize(1);
        assertThat(left.getFirst().reason()).isEqualTo(LeaveReason.DISCONNECT_TIMEOUT);
    }

    @Test
    void 확정_없던_예약_만료는_ParticipantLeft를_발행하지_않는다() {
        String code = createRoom();
        join(1L, code);

        roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(eventsOf(ParticipantLeftEvent.class)).isEmpty();
    }

    @Test
    void 마지막_인원_퇴장_시_RoomClosed_LAST_LEFT가_발행된다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");

        roomService.leave(roomId, 1L);

        List<RoomClosedEvent> closed = eventsOf(RoomClosedEvent.class);
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().reason()).isEqualTo(CloseReason.LAST_LEFT);
    }

    @Test
    void 빈_방_만료_시_RoomClosed_EMPTY_EXPIRED가_발행된다() {
        roomService.create(1L);

        roomService.cleanupExpired(Instant.now().plusSeconds(601));

        List<RoomClosedEvent> closed = eventsOf(RoomClosedEvent.class);
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().reason()).isEqualTo(CloseReason.EMPTY_EXPIRED);
    }

    @Test
    void 생성된_방과_참여_이벤트는_같은_roomUid를_공유한다() {
        String code = createRoom();
        long roomId = join(1L, code).response().roomId();
        roomService.confirmStomp(roomId, 1L, "session-1");
        roomService.leave(roomId, 1L);

        var createdUid = eventsOf(RoomCreatedEvent.class).getFirst().roomUid();
        assertThat(eventsOf(ParticipantJoinedEvent.class).getFirst().roomUid()).isEqualTo(createdUid);
        assertThat(eventsOf(ParticipantLeftEvent.class).getFirst().roomUid()).isEqualTo(createdUid);
        assertThat(eventsOf(RoomClosedEvent.class).getFirst().roomUid()).isEqualTo(createdUid);
    }
}
