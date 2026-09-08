package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.service.RoomService.JoinResult;
import project.study.room.service.RoomService.LeaveResult;
import project.study.room.support.RoomProbe;
import project.study.room.support.RoomTestBase;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomServiceTest extends RoomTestBase {

    @Test
    void 방을_만들면_숫자_4자리_초대코드가_발급된다() {
        RoomCreateResponse response = roomService.create(owner);

        assertThat(response.roomId()).isNotNull();
        assertThat(response.inviteCode()).matches("\\d{4}");
        assertThat(response.emptyTtlSeconds()).isEqualTo(600);
        assertThat(rooms.isOpen(response.roomId())).isTrue();
    }

    @Test
    void 생성만으로는_입장_상태가_아니다() {
        roomService.create(owner);

        assertThat(roomState.getRoomIdForUser(owner)).isNull();
    }

    @Test
    void 초대코드로_입장하면_참가자가_추가된다() {
        String code = createRoom();
        long userId = user();

        JoinResult result = join(userId, code);

        assertThat(result.response().graceRejoin()).isFalse();
        assertThat(result.autoLeave()).isNull();
        assertThat(roomState.getRoomIdForUser(userId))
                .isEqualTo(result.response().roomId());
        assertThat(probe.participation(result.response().roomId(), userId))
                .get()
                .extracting(RoomProbe.Participation::stompConfirmed)
                .isEqualTo(false);
    }

    @Test
    void 형식이_틀린_초대코드는_400이다() {
        long userId = user();
        assertThatThrownBy(() -> join(userId, "12a4")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(userId, "123")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(userId, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 없는_초대코드는_404다() {
        long userId = user();
        assertThatThrownBy(() -> join(userId, unusedCode())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 정원_6명_초과_시_ConflictException이_발생한다() {
        String code = createRoom();
        for (int i = 0; i < 6; i++) {
            join(user(), code);
        }

        long seventh = user();
        assertThatThrownBy(() -> join(seventh, code)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 다른_방에_있으면_자동_퇴장_후_새_방에_입장한다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        join(user(), codeA); // roomA가 소멸하지 않도록 다른 참가자 유지

        JoinResult result = join(userId, codeB);

        assertThat(result.autoLeave()).isNotNull();
        assertThat(result.autoLeave().roomId()).isEqualTo(roomA);
        assertThat(roomState.getRoomIdForUser(userId))
                .isEqualTo(result.response().roomId());
        assertThat(rooms.isOpen(roomA)).isTrue();
    }

    @Test
    void 확정된_뒤_방을_옮기면_옛_방_이력에_SWITCHED_ROOM이_남는다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        confirm(roomA, userId, "s1");

        join(userId, codeB);

        RoomProbe.Participation history = probe.participation(roomA, userId).orElseThrow();
        assertThat(history.leftAt()).isNotNull();
        assertThat(history.leaveReason()).isEqualTo("SWITCHED_ROOM");
        assertThat(rooms.isOpen(roomA)).as("마지막 1명이 옮겨 갔으니 옛 방은 닫힌다").isFalse();
    }

    @Test
    void 마지막_1명이_퇴장하면_방과_코드가_소멸한다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        LeaveResult result = roomService.leave(roomId, userId);

        assertThat(result).isEqualTo(new LeaveResult(true, false));
        assertThat(roomService.roomExists(roomId)).isFalse();
        long next = user();
        assertThatThrownBy(() -> join(next, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 남은_인원이_있으면_방이_유지된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        join(user(), code);

        LeaveResult result = roomService.leave(roomId, userId);

        assertThat(result).isEqualTo(new LeaveResult(true, true));
        assertThat(roomService.roomExists(roomId)).isTrue();
        assertThat(roomService.leave(roomId, userId)).as("두 번째 퇴장은 없음").isEqualTo(LeaveResult.NONE);
    }

    @Test
    void 확정된_참가자의_퇴장은_left_at과_사유와_마지막_순공초를_남긴다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "s1");
        roomState.updateState(roomId, userId, "s1", null, null, 1500);

        roomService.leave(roomId, userId);

        RoomProbe.Participation history = probe.participation(roomId, userId).orElseThrow();
        assertThat(history.joinedAt()).isNotNull();
        assertThat(history.leftAt()).isNotNull();
        assertThat(history.leaveReason()).isEqualTo("EXPLICIT");
        assertThat(history.focusSec()).isEqualTo(1500);
    }

    @Test
    void 확정_없는_예약은_퇴장_시_이력_없이_지워진다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        roomService.leave(roomId, userId);

        assertThat(probe.participation(roomId, userId)).isEmpty();
    }

    @Test
    void 유예_기간_내_재입장하면_graceRejoin이_true다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomState.updateState(roomId, userId, "session-1", true, null, null);
        roomService.handleDisconnect("session-1");

        JoinResult result = join(userId, code);

        assertThat(result.response().graceRejoin()).isTrue();
        assertThat(result.response().cameraOn()).isTrue();
        assertThat(probe.participation(roomId, userId))
                .get()
                .extracting(RoomProbe.Participation::stompConfirmed)
                .as("복귀는 예약 상태로 되돌려 30초 안에 다시 구독하게 한다")
                .isEqualTo(false);
    }

    @Test
    void 대상_방이_가득_차면_기존_방_자리를_잃지_않는다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        for (int i = 0; i < 6; i++) {
            join(user(), codeB);
        }

        assertThatThrownBy(() -> join(userId, codeB)).isInstanceOf(ConflictException.class);
        assertThat(roomState.getRoomIdForUser(userId)).isEqualTo(roomA);
    }
}
