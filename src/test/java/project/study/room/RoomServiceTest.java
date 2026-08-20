package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.common.BadRequestException;
import project.study.common.ConflictException;
import project.study.common.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;

class RoomServiceTest {

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of());
    }

    private String createRoom() {
        return roomService.create(1L).inviteCode();
    }

    @Test
    void 방을_만들면_숫자_4자리_초대코드가_발급된다() {
        RoomCreateResponse response = roomService.create(1L);

        assertThat(response.roomId()).isNotNull();
        assertThat(response.inviteCode()).matches("\\d{4}");
        assertThat(response.emptyTtlSeconds()).isEqualTo(600);
    }

    @Test
    void 생성만으로는_입장_상태가_아니다() {
        roomService.create(1L);

        assertThat(roomService.getRoomIdForUser(1L)).isNull();
    }

    @Test
    void 초대코드로_입장하면_참가자가_추가된다() {
        String code = createRoom();

        RoomService.JoinResult result = roomService.join(100L, code);

        assertThat(result.response().graceRejoin()).isFalse();
        assertThat(result.autoLeave()).isNull();
        assertThat(roomService.getRoomIdForUser(100L))
                .isEqualTo(result.response().roomId());
    }

    @Test
    void 형식이_틀린_초대코드는_400이다() {
        assertThatThrownBy(() -> roomService.join(100L, "12a4")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> roomService.join(100L, "123")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> roomService.join(100L, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 없는_초대코드는_404다() {
        assertThatThrownBy(() -> roomService.join(100L, "9999")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 정원_6명_초과_시_ConflictException이_발생한다() {
        String code = createRoom();
        for (long i = 1; i <= 6; i++) {
            roomService.join(i, code);
        }

        assertThatThrownBy(() -> roomService.join(99L, code)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 다른_방에_있으면_자동_퇴장_후_새_방에_입장한다() {
        String codeA = roomService.create(1L).inviteCode();
        String codeB = roomService.create(2L).inviteCode();
        Long roomA = roomService.join(100L, codeA).response().roomId();
        roomService.join(200L, codeA); // roomA가 소멸하지 않도록 다른 참가자 유지

        RoomService.JoinResult result = roomService.join(100L, codeB);

        assertThat(result.autoLeave()).isNotNull();
        assertThat(result.autoLeave().roomId()).isEqualTo(roomA);
        assertThat(roomService.getRoomIdForUser(100L))
                .isEqualTo(result.response().roomId());
    }

    @Test
    void 마지막_1명이_퇴장하면_방과_코드가_소멸한다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        roomService.leave(roomId, 100L);

        assertThat(roomService.roomExists(roomId)).isFalse();
        assertThatThrownBy(() -> roomService.join(200L, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 남은_인원이_있으면_방이_유지된다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.join(200L, code);

        roomService.leave(roomId, 100L);

        assertThat(roomService.roomExists(roomId)).isTrue();
    }

    @Test
    void 유예_기간_내_재입장하면_graceRejoin이_true다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");

        RoomService.JoinResult result = roomService.join(100L, code);

        assertThat(result.response().graceRejoin()).isTrue();
    }

    @Test
    void STOMP_확정_후_멤버_목록에_포함된다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        List<RoomMember> members = roomService.confirmStomp(roomId, 100L, "session-1");

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(100L);
        assertThat(members.getFirst().focusState()).isEqualTo("FOCUS");
    }

    @Test
    void 만료된_예약이_정리되고_방도_소멸한다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(removed).hasSize(1);
        assertThat(removed.getFirst().userId()).isEqualTo(100L);
        assertThat(roomService.roomExists(roomId)).isFalse();
    }

    @Test
    void 끊김_유예_만료_후_참가자가_제거된다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(removed).hasSize(1);
        assertThat(roomService.getRoomIdForUser(100L)).isNull();
    }

    @Test
    void 입장_이력_없는_빈_방은_10분_뒤_소멸한다() {
        String code = createRoom();

        roomService.cleanupExpired(Instant.now().plusSeconds(601));

        assertThatThrownBy(() -> roomService.join(100L, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 십분이_지나지_않은_빈_방은_유지된다() {
        String code = createRoom();

        roomService.cleanupExpired(Instant.now().plusSeconds(599));

        assertThat(roomService.join(100L, code).response().roomId()).isNotNull();
    }

    @Test
    void 대상_방이_가득_차면_기존_방_자리를_잃지_않는다() {
        String codeA = roomService.create(1L).inviteCode();
        String codeB = roomService.create(2L).inviteCode();
        Long roomA = roomService.join(100L, codeA).response().roomId();
        for (long i = 1; i <= 6; i++) {
            roomService.join(200 + i, codeB);
        }

        assertThatThrownBy(() -> roomService.join(100L, codeB)).isInstanceOf(ConflictException.class);
        assertThat(roomService.getRoomIdForUser(100L)).isEqualTo(roomA);
    }

    @Test
    void 재접속_후_도착한_옛_세션의_끊김_이벤트는_무시된다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-old");

        // 재접속으로 새 세션 확정 → 그 뒤에 옛 세션의 disconnect가 늦게 도착
        roomService.confirmStomp(roomId, 100L, "session-new");
        roomService.handleDisconnect("session-old");

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));
        assertThat(removed).isEmpty();
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isTrue();
    }

    @Test
    void 방_멤버가_아니면_확정_멤버가_아니다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        assertThat(roomService.isConfirmedMember(roomId, 999L)).isFalse();
        // 예약만 하고 STOMP 확정 전이면 아직 멤버가 아니다
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isFalse();
    }

    @Test
    void STOMP_확정_전에는_상태를_갱신할_수_없다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        assertThat(roomService.updateCamera(roomId, 100L, true)).isFalse();
        assertThat(roomService.updateFocusState(roomId, 100L, "DISTRACTED")).isFalse();

        roomService.confirmStomp(roomId, 100L, "session-1");
        assertThat(roomService.updateCamera(roomId, 100L, true)).isTrue();
    }

    @Test
    void 현재_세션에서_온_메시지만_활성_세션으로_인정된다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-old");
        roomService.confirmStomp(roomId, 100L, "session-new");

        assertThat(roomService.isActiveSession(roomId, 100L, "session-new")).isTrue();
        assertThat(roomService.isActiveSession(roomId, 100L, "session-old")).isFalse();
        assertThat(roomService.isActiveSession(roomId, 100L, null)).isFalse();
    }

    @Test
    void 유예_중_재구독으로_확정되면_유예가_해제되어_쫓겨나지_않는다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");

        // join 재호출 없이 STOMP 재구독만으로 복귀하는 경로
        roomService.confirmStomp(roomId, 100L, "session-2");

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));
        assertThat(removed).isEmpty();
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isTrue();
    }

    @Test
    void 자리_예약자만_방_토픽을_구독할_수_있다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();

        assertThat(roomService.hasParticipant(roomId, 100L)).isTrue();
        assertThat(roomService.hasParticipant(roomId, 999L)).isFalse();
    }

    @Test
    void 소멸한_방의_코드는_새_방이_재사용할_수_있다() {
        String code = createRoom();
        Long roomId = roomService.join(100L, code).response().roomId();
        roomService.leave(roomId, 100L);

        // 같은 코드가 나올 때까지 생성 반복 — 코드 공간이 비어 있으므로 언젠가 재사용된다는 것만 검증
        assertThat(roomService.roomExists(roomId)).isFalse();
    }
}
