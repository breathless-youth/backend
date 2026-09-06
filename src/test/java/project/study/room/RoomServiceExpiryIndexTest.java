package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.room.service.RoomService;

/**
 * 만료 후보 인덱스 정합성 (BY-593). cleanupExpired가 전체 방 대신 후보(미확정 예약·끊김 유예·빈 방)만 검사하므로,
 * 상태 전이마다 후보 등록/해제가 정확해야 한다 — 빠지면 영원히 정리되지 않고, 잘못 남으면 정상 참가자가 쫓겨난다.
 */
class RoomServiceExpiryIndexTest {

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of(), event -> {});
    }

    private String createRoom() {
        return roomService.create(1L).inviteCode();
    }

    private RoomService.JoinResult join(Long userId, String code) {
        return roomService.join(userId, code, "포메" + userId, null, null);
    }

    private List<RoomService.AutoLeave> cleanupAfter(long seconds) {
        return roomService.cleanupExpired(Instant.now().plusSeconds(seconds));
    }

    @Test
    void 확정된_참가자는_예약_TTL이_지나도_유지된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");

        assertThat(cleanupAfter(31)).isEmpty();
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isTrue();
    }

    @Test
    void 유예복원_후_확정하지_않으면_예약_만료로_제거된다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");
        // 유예 중 재입장(복원) — 다시 미확정 예약 상태가 되므로 예약 만료 후보로 남아야 한다
        join(100L, code);

        assertThat(cleanupAfter(31)).hasSize(1);
        assertThat(roomService.getRoomIdForUser(100L)).isNull();
    }

    @Test
    void 확정된_참가자가_끊긴_뒤_재확정하면_유예_만료되지_않는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        roomService.handleDisconnect("session-1");
        join(100L, code);
        roomService.confirmStomp(roomId, 100L, "session-2");

        assertThat(cleanupAfter(31)).isEmpty();
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isTrue();
    }

    @Test
    void 여러_방_중_미확정_예약만_만료되고_확정_방은_유지된다() {
        String codeA = createRoom();
        Long roomA = join(100L, codeA).response().roomId();
        roomService.confirmStomp(roomA, 100L, "session-A");
        String codeB = createRoom();
        Long roomB = join(200L, codeB).response().roomId();

        assertThat(cleanupAfter(31)).extracting(RoomService.AutoLeave::userId).containsExactly(200L);
        assertThat(roomService.isConfirmedMember(roomA, 100L)).isTrue();
        assertThat(roomService.roomExists(roomB)).isFalse();
    }

    @Test
    void 확정된_멤버가_같은_방에_중복_join해도_만료되지_않는다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");
        // 연결 유지 중인 멤버의 중복 join(재시도 분기) — 확정 상태가 유지돼야 하고 후보로 되돌아가면 안 된다
        join(100L, code);

        assertThat(cleanupAfter(31)).isEmpty();
        assertThat(roomService.isConfirmedMember(roomId, 100L)).isTrue();
    }

    @Test
    void 첫_입장이_생긴_방은_빈_방_TTL_대상에서_빠진다() {
        String code = createRoom();
        Long roomId = join(100L, code).response().roomId();
        roomService.confirmStomp(roomId, 100L, "session-1");

        // 빈 방 TTL(600s)이 지나도 참가자가 있으므로 소멸하지 않아야 한다
        cleanupAfter(601);

        assertThat(roomService.roomExists(roomId)).isTrue();
    }
}
