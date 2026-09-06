package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.common.ErrorCode;
import project.study.common.NotFoundException;
import project.study.room.service.RoomService;

/** BY-436 초대코드 404 에러 코드 구분 — RoomServiceTest에서 분리(파일 400줄 제한). */
class RoomInviteCodeErrorTest {

    // 방 ID는 프로덕션에서 DB 시퀀스(RoomIdAllocator)가 발급한다 — 테스트는 카운터로 대신한다 (BY-626)
    private static final java.util.concurrent.atomic.AtomicLong ROOM_IDS =
            new java.util.concurrent.atomic.AtomicLong(1_000_000);

    private static long nextRoomId() {
        return ROOM_IDS.incrementAndGet();
    }

    private static final long CLOSED_CODE_TTL_SECONDS = 600;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of(), event -> {});
    }

    private String createRoom() {
        return roomService.create(1L, nextRoomId()).inviteCode();
    }

    private static void assertNotFoundWithCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    // 프로필 값이 중요하지 않은 테스트용 기본 join — 닉네임·목표는 join 시점에 호출자가 전달한다
    private RoomService.JoinResult join(Long userId, String code) {
        return roomService.join(userId, code, "포메" + userId, null, null);
    }

    @Test
    void 발급된_적_없는_코드는_INVITE_CODE_NOT_FOUND다() {
        assertNotFoundWithCode(() -> join(100L, "9999"), ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    void 마지막_1명이_퇴장해_소멸한_방의_코드는_ROOM_CLOSED다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.leave(roomId, 100L);

        assertNotFoundWithCode(() -> join(200L, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 입장_없이_만료돼_소멸한_빈_방의_코드도_ROOM_CLOSED다() {
        String code = createRoom();

        roomService.cleanupExpired(Instant.now().plusSeconds(601));

        assertNotFoundWithCode(() -> join(100L, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 소멸_10분이_지난_코드는_다시_INVITE_CODE_NOT_FOUND가_된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.leave(roomId, 100L);

        roomService.cleanupExpired(Instant.now().plusSeconds(CLOSED_CODE_TTL_SECONDS + 1));

        assertNotFoundWithCode(() -> join(200L, code), ErrorCode.INVITE_CODE_NOT_FOUND);
    }
}
