package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.common.BadRequestException;
import project.study.common.ConflictException;
import project.study.common.ErrorCode;
import project.study.common.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomMember;
import project.study.room.service.RoomService;

class RoomServiceTest {

    private static final long CLOSED_CODE_TTL_SECONDS = 600;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of(), event -> {});
    }

    private String createRoom() {
        return roomService.create(1L).inviteCode();
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

        RoomService.JoinResult result = join(100L, code);

        assertThat(result.response().graceRejoin()).isFalse();
        assertThat(result.autoLeave()).isNull();
        assertThat(roomService.getRoomIdForUser(100L))
                .isEqualTo(result.response().roomId());
    }

    @Test
    void 형식이_틀린_초대코드는_400이다() {
        assertThatThrownBy(() -> join(100L, "12a4")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(100L, "123")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(100L, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 없는_초대코드는_404다() {
        assertThatThrownBy(() -> join(100L, "9999")).isInstanceOf(NotFoundException.class);
    }

    // 아래 네 개는 BY-436 — 같은 404라도 클라이언트가 안내 문구를 고를 수 있어야 한다

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

    @Test
    void 정원_6명_초과_시_ConflictException이_발생한다() {
        String code = createRoom();
        for (long i = 1; i <= 6; i++) {
            join(i, code);
        }

        assertThatThrownBy(() -> join(99L, code)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 다른_방에_있으면_자동_퇴장_후_새_방에_입장한다() {
        String codeA = roomService.create(1L).inviteCode();
        String codeB = roomService.create(2L).inviteCode();
        Long roomA = join(100L, codeA).response().roomId();
        join(200L, codeA); // roomA가 소멸하지 않도록 다른 참가자 유지

        RoomService.JoinResult result = join(100L, codeB);

        assertThat(result.autoLeave()).isNotNull();
        assertThat(result.autoLeave().roomId()).isEqualTo(roomA);
        assertThat(roomService.getRoomIdForUser(100L))
                .isEqualTo(result.response().roomId());
    }

    @Test
    void 마지막_1명이_퇴장하면_방과_코드가_소멸한다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();

        roomService.leave(roomId, 100L);

        assertThat(roomService.roomExists(roomId)).isFalse();
        assertThatThrownBy(() -> join(200L, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 남은_인원이_있으면_방이_유지된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        join(200L, code);

        roomService.leave(roomId, 100L);

        assertThat(roomService.roomExists(roomId)).isTrue();
    }

    @Test
    void 유예_기간_내_재입장하면_graceRejoin이_true다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");

        RoomService.JoinResult result = join(100L, code);

        assertThat(result.response().graceRejoin()).isTrue();
    }

    @Test
    void STOMP_확정_후_멤버_목록에_포함된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();

        List<RoomMember> members = roomService.confirmStomp(roomId, 100L, "session-1");

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(100L);
        assertThat(members.getFirst().focusState()).isEqualTo("FOCUS");
    }

    @Test
    void 만료된_예약이_정리되고_방도_소멸한다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();

        List<RoomService.AutoLeave> removed =
                roomService.cleanupExpired(Instant.now().plusSeconds(31));

        assertThat(removed).hasSize(1);
        assertThat(removed.getFirst().userId()).isEqualTo(100L);
        assertThat(roomService.roomExists(roomId)).isFalse();
    }

    @Test
    void 끊김_유예_만료_후_참가자가_제거된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
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

        assertThatThrownBy(() -> join(100L, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 십분이_지나지_않은_빈_방은_유지된다() {
        String code = createRoom();

        roomService.cleanupExpired(Instant.now().plusSeconds(599));

        assertThat(join(100L, code).response().roomId()).isNotNull();
    }

    @Test
    void 대상_방이_가득_차면_기존_방_자리를_잃지_않는다() {
        String codeA = roomService.create(1L).inviteCode();
        String codeB = roomService.create(2L).inviteCode();
        Long roomA = join(100L, codeA).response().roomId();
        for (long i = 1; i <= 6; i++) {
            join(200 + i, codeB);
        }

        assertThatThrownBy(() -> join(100L, codeB)).isInstanceOf(ConflictException.class);
        assertThat(roomService.getRoomIdForUser(100L)).isEqualTo(roomA);
    }

    @Test
    void 재접속_후_도착한_옛_세션의_끊김_이벤트는_무시된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
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
        Long roomId = join(100L, code).response().roomId();

        assertThat(roomService.isConfirmedMember(roomId, 999L)).isFalse();
        // 예약만 하고 STOMP 확정 전이면 아직 멤버가 아니다
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isFalse();
    }

    @Test
    void STOMP_확정_전에는_상태를_갱신할_수_없다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();

        assertThat(roomService.updateCamera(roomId, 100L, true)).isFalse();
        assertThat(roomService.updateFocusState(roomId, 100L, "DISTRACTED")).isFalse();
        assertThat(roomService.updateStudyTime(roomId, 100L, 300)).isFalse();

        roomService.confirmStomp(roomId, 100L, "session-1");
        assertThat(roomService.updateCamera(roomId, 100L, true)).isTrue();
    }

    @Test
    void 스냅샷_멤버에_닉네임과_목표가_실린다() {
        String code = createRoom();
        Long roomId = roomService
                .join(100L, code, "숨벅찬포메", "정처기 합격", "CERTIFICATE")
                .response()
                .roomId();

        List<RoomMember> members = roomService.confirmStomp(roomId, 100L, "session-1");

        assertThat(members.getFirst().nickname()).isEqualTo("숨벅찬포메");
        assertThat(members.getFirst().goal()).isEqualTo("정처기 합격");
    }

    // 스냅샷 재요청(BY-442) — 인가와 조회가 한 번의 원자 호출이어야 한다 (검사-조회 사이 퇴장 레이스 차단)
    @Test
    void 활성_세션_멤버는_스냅샷_재요청으로_멤버_목록을_받는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        join(200L, code);
        roomService.confirmStomp(roomId, 200L, "session-2");

        List<RoomMember> members = roomService.getMembersForActiveSession(roomId, 100L, "session-1");

        assertThat(members).extracting(RoomMember::userId).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    void 옛_세션의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-old");
        roomService.confirmStomp(roomId, 100L, "session-new");

        assertThat(roomService.getMembersForActiveSession(roomId, 100L, "session-old"))
                .isEmpty();
    }

    @Test
    void 확정_전_참가자의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();

        assertThat(roomService.getMembersForActiveSession(roomId, 100L, "session-1"))
                .isEmpty();
    }

    @Test
    void 비멤버와_없는_방의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");

        assertThat(roomService.getMembersForActiveSession(roomId, 999L, "session-x"))
                .isEmpty();
        assertThat(roomService.getMembersForActiveSession(roomId + 1, 100L, "session-1"))
                .isEmpty();
    }

    @Test
    void 마지막_순공시간이_스냅샷에_실린다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");

        // 확정 직후 초기값은 0 — 새 입장자가 빈 값을 보지 않는다
        assertThat(roomService.getMembers(roomId).getFirst().studySeconds()).isZero();

        assertThat(roomService.updateStudyTime(roomId, 100L, 1500)).isTrue();
        assertThat(roomService.getMembers(roomId).getFirst().studySeconds()).isEqualTo(1500);
    }

    @Test
    void 현재_세션에서_온_메시지만_활성_세션으로_인정된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-old");
        roomService.confirmStomp(roomId, 100L, "session-new");

        assertThat(roomService.isActiveSession(roomId, 100L, "session-new")).isTrue();
        assertThat(roomService.isActiveSession(roomId, 100L, "session-old")).isFalse();
        assertThat(roomService.isActiveSession(roomId, 100L, null)).isFalse();
    }

    @Test
    void 유예_중_재구독으로_확정되면_유예가_해제되어_쫓겨나지_않는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
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
        Long roomId = join(100L, code).response().roomId();

        assertThat(roomService.hasParticipant(roomId, 100L)).isTrue();
        assertThat(roomService.hasParticipant(roomId, 999L)).isFalse();
    }

    /**
     * 코드를 1만 개 공간에서 무작위로 뽑으므로 "재발급되지 않았다"를 직접 관측할 수는 없다 —
     * 대신 클라이언트가 실제로 보는 계약을 검증한다. 묘비 기간 안에 방이 여럿 새로 생겨도
     * 소멸한 코드로 들어오면 새 방 입장이 아니라 ROOM_CLOSED여야 한다 (BY-436).
     */
    @Test
    void 소멸한_코드는_묘비_기간_동안_다른_방에_재발급되지_않는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.leave(roomId, 100L);
        for (int i = 0; i < 50; i++) {
            roomService.create(1L);
        }

        assertThat(roomService.roomExists(roomId)).isFalse();
        assertNotFoundWithCode(() -> join(200L, code), ErrorCode.ROOM_CLOSED);
    }
}
