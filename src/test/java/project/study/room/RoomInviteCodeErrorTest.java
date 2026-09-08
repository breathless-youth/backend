package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.room.support.RoomTestBase;

/** BY-436 초대코드 404 에러 코드 구분. 묘비는 닫힌 행(closed_at)이다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomInviteCodeErrorTest extends RoomTestBase {

    private static void assertNotFoundWithCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    @Test
    void 발급된_적_없는_코드는_INVITE_CODE_NOT_FOUND다() {
        long userId = user();
        assertNotFoundWithCode(() -> join(userId, unusedCode()), ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    void 마지막_1명이_퇴장해_소멸한_방의_코드는_ROOM_CLOSED다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);

        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 입장_없이_만료돼_소멸한_빈_방의_코드도_ROOM_CLOSED다() {
        String code = createRoom();

        cleanupAfter(601);

        long userId = user();
        assertNotFoundWithCode(() -> join(userId, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 소멸_10분이_지난_코드는_다시_INVITE_CODE_NOT_FOUND가_된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);
        probe.closeRoomAt(roomId, Instant.now().minusSeconds(601)); // 닫힌 시각을 10분 전으로

        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    /**
     * 코드를 1만 개 공간에서 무작위로 뽑으므로 "재발급되지 않았다"를 직접 관측할 수는 없다 —
     * 대신 클라이언트가 실제로 보는 계약을 검증한다. 묘비 기간 안에 방이 여럿 새로 생겨도
     * 소멸한 코드로 들어오면 새 방 입장이 아니라 ROOM_CLOSED여야 한다 (BY-436).
     */
    @Test
    void 소멸한_코드는_묘비_기간_동안_다른_방에_재발급되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);
        for (int i = 0; i < 50; i++) {
            roomService.create(owner);
        }

        assertThat(roomService.roomExists(roomId)).isFalse();
        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.ROOM_CLOSED);
    }
}
