package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.common.ConflictException;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;

class RoomServiceTest {

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of());
    }

    @Test
    void 룸에_입장하면_참가자가_추가된다() {
        RoomService.EnterResult result = roomService.enter(1L, 100L);

        assertThat(result.response().graceRejoin()).isFalse();
        assertThat(result.autoLeave()).isNull();
        assertThat(roomService.getRoomIdForUser(100L)).isEqualTo(1L);
    }

    @Test
    void 다른_룸에_있으면_자동_퇴장_후_새_룸에_입장한다() {
        roomService.enter(1L, 100L);

        RoomService.EnterResult result = roomService.enter(2L, 100L);

        assertThat(result.autoLeave()).isNotNull();
        assertThat(result.autoLeave().roomId()).isEqualTo(1L);
        assertThat(result.autoLeave().userId()).isEqualTo(100L);
        assertThat(roomService.getRoomIdForUser(100L)).isEqualTo(2L);
    }

    @Test
    void 정원_8명_초과_시_ConflictException이_발생한다() {
        for (long i = 1; i <= 8; i++) {
            roomService.enter(1L, i);
        }

        assertThatThrownBy(() -> roomService.enter(1L, 99L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ROOM_FULL");
    }

    @Test
    void 유예_기간_내_재입장하면_graceRejoin이_true다() {
        roomService.enter(1L, 100L);
        roomService.confirmStomp(1L, 100L, "session-1");

        roomService.handleDisconnect("session-1");

        RoomService.EnterResult result = roomService.enter(1L, 100L);
        assertThat(result.response().graceRejoin()).isTrue();
    }

    @Test
    void 퇴장하면_참가자가_제거된다() {
        roomService.enter(1L, 100L);

        boolean removed = roomService.leave(1L, 100L);

        assertThat(removed).isTrue();
        assertThat(roomService.getRoomIdForUser(100L)).isNull();
    }

    @Test
    void STOMP_확정_후_멤버_목록에_포함된다() {
        roomService.enter(1L, 100L);

        List<RoomMember> members = roomService.confirmStomp(1L, 100L, "session-1");

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(100L);
        assertThat(members.getFirst().focusState()).isEqualTo("FOCUS");
    }

    @Test
    void 만료된_예약이_정리된다() {
        roomService.enter(1L, 100L);

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(removed).hasSize(1);
        assertThat(removed.getFirst().userId()).isEqualTo(100L);
        assertThat(roomService.getRoomIdForUser(100L)).isNull();
    }

    @Test
    void 끊김_유예_만료_후_참가자가_제거된다() {
        roomService.enter(1L, 100L);
        roomService.confirmStomp(1L, 100L, "session-1");
        roomService.handleDisconnect("session-1");

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(removed).hasSize(1);
        assertThat(roomService.getRoomIdForUser(100L)).isNull();
    }

    @Test
    void 같은_룸에_재입장하면_자동퇴장_없이_기존_자리를_재활용한다() {
        roomService.enter(1L, 100L);

        RoomService.EnterResult result = roomService.enter(1L, 100L);

        assertThat(result.autoLeave()).isNull();
    }
}
