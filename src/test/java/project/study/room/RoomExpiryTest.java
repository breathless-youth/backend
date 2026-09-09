package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.NotFoundException;
import project.study.room.service.AutoLeave;
import project.study.room.support.RoomTestBase;

/**
 * 만료 정합성. 예약 30초·유예 30초·빈 방 600초. 잘못 만료되면 정상 참가자가 쫓겨나고, 안 만료되면 자리·방이 샌다.
 * (옛 RoomServiceTest의 만료 케이스 + BY-593 RoomServiceExpiryIndexTest를 흡수)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomExpiryTest extends RoomTestBase {

    @Test
    void 만료된_예약이_정리되고_방도_소멸한다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        List<AutoLeave> removed = cleanupAfter(31, roomId);

        assertThat(removed).containsExactly(new AutoLeave(roomId, userId));
        assertThat(roomService.roomExists(roomId)).isFalse();
    }

    @Test
    void 끊김_유예_만료_후_참가자가_제거된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");

        List<AutoLeave> removed = cleanupAfter(31, roomId);

        assertThat(removed).containsExactly(new AutoLeave(roomId, userId));
        assertThat(roomState.getRoomIdForUser(userId)).isNull();
        assertThat(probe.participation(roomId, userId).orElseThrow().leaveReason())
                .isEqualTo("DISCONNECT_TIMEOUT");
    }

    @Test
    void 입장_이력_없는_빈_방은_10분_뒤_소멸한다() {
        String code = createRoom();

        cleanupAfter(601);

        long userId = user();
        assertThatThrownBy(() -> join(userId, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 십분이_지나지_않은_빈_방은_유지된다() {
        String code = createRoom();

        cleanupAfter(599);

        assertThat(join(user(), code).response().roomId()).isNotNull();
    }

    @Test
    void 확정된_참가자는_예약_TTL이_지나도_유지된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 유예복원_후_확정하지_않으면_예약_만료로_제거된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");
        // 유예 중 재입장(복원) — 다시 미확정 예약 상태가 되므로 예약 만료 대상이어야 한다
        join(userId, code);

        assertThat(cleanupAfter(31, roomId)).hasSize(1);
        assertThat(roomState.getRoomIdForUser(userId)).isNull();
    }

    @Test
    void 확정된_참가자가_끊긴_뒤_재확정하면_유예_만료되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");
        join(userId, code);
        confirm(roomId, userId, "session-2");

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 여러_방_중_미확정_예약만_만료되고_확정_방은_유지된다() {
        String codeA = createRoom();
        long a = user();
        long roomA = join(a, codeA).response().roomId();
        confirm(roomA, a, "session-A");
        String codeB = createRoom();
        long b = user();
        long roomB = join(b, codeB).response().roomId();

        assertThat(cleanupAfter(31, roomA, roomB)).extracting(AutoLeave::userId).containsExactly(b);
        assertThat(roomState.isConfirmedMember(roomA, a)).isTrue();
        assertThat(roomService.roomExists(roomB)).isFalse();
    }

    @Test
    void 확정된_멤버가_같은_방에_중복_join해도_만료되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        // 연결 유지 중인 멤버의 중복 join(재시도 분기) — 확정 상태가 유지돼야 한다
        join(userId, code);

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 첫_입장이_생긴_방은_빈_방_TTL_대상에서_빠진다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        cleanupAfter(601);

        assertThat(roomService.roomExists(roomId)).isTrue();
    }
}
