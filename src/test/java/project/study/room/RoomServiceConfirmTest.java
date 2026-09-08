package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.dto.RoomMember;
import project.study.room.support.RoomTestBase;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomServiceConfirmTest extends RoomTestBase {

    @Test
    void STOMP_확정_후_멤버_목록에_포함된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        List<RoomMember> members = confirm(roomId, userId, "session-1");

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(userId);
        assertThat(members.getFirst().focusState()).isEqualTo("FOCUS");
        assertThat(members.getFirst().disconnected()).isFalse();
        assertThat(probe.participation(roomId, userId).orElseThrow().joinedAt()).isNotNull();
    }

    @Test
    void 스냅샷_멤버에_닉네임과_목표가_실린다() {
        String code = createRoom();
        long userId = user();
        long roomId = roomService
                .join(userId, code, "숨벅찬포메", "정처기 합격", "CERTIFICATE")
                .response()
                .roomId();

        List<RoomMember> members = confirm(roomId, userId, "session-1");

        assertThat(members.getFirst().nickname()).isEqualTo("숨벅찬포메");
        assertThat(members.getFirst().goal()).isEqualTo("정처기 합격");
    }

    @Test
    void 방_멤버가_아니면_확정_멤버가_아니다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.isConfirmedMember(roomId, 999_999L)).isFalse();
        // 예약만 하고 STOMP 확정 전이면 아직 멤버가 아니다
        assertThat(roomState.isConfirmedMember(roomId, userId)).isFalse();
    }

    @Test
    void STOMP_확정_전에는_상태를_갱신할_수_없다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.updateState(roomId, userId, "session-1", true, "DISTRACTED", 300))
                .isFalse();

        confirm(roomId, userId, "session-1");
        assertThat(roomState.updateState(roomId, userId, "session-1", true, null, null))
                .isTrue();
    }

    @Test
    void 마지막_순공시간이_스냅샷에_실린다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        // 확정 직후 초기값은 0 — 새 입장자가 빈 값을 보지 않는다
        assertThat(roomState.getMembers(roomId).getFirst().focusSec()).isZero();

        assertThat(roomState.updateState(roomId, userId, "session-1", null, null, 1500))
                .isTrue();
        assertThat(roomState.getMembers(roomId).getFirst().focusSec()).isEqualTo(1500);
    }

    // 스냅샷 재요청(BY-442) — 인가와 조회가 한 번의 원자 호출이어야 한다
    @Test
    void 활성_세션_멤버는_스냅샷_재요청으로_멤버_목록을_받는다() {
        String code = createRoom();
        long me = user();
        long peer = user();
        long roomId = join(me, code).response().roomId();
        confirm(roomId, me, "session-1");
        join(peer, code);
        confirm(roomId, peer, "session-2");

        List<RoomMember> members = roomState.getMembersForActiveSession(roomId, me, "session-1");

        assertThat(members).extracting(RoomMember::userId).containsExactlyInAnyOrder(me, peer);
    }

    @Test
    void 옛_세션의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomState.getMembersForActiveSession(roomId, userId, "session-old"))
                .isEmpty();
    }

    @Test
    void 확정_전_참가자의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.getMembersForActiveSession(roomId, userId, "session-1"))
                .isEmpty();
    }

    @Test
    void 비멤버와_없는_방의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        assertThat(roomState.getMembersForActiveSession(roomId, 999_999L, "session-x"))
                .isEmpty();
        assertThat(roomState.getMembersForActiveSession(roomId + 1_000_000, userId, "session-1"))
                .isEmpty();
    }

    @Test
    void 현재_세션에서_온_메시지만_활성_세션으로_인정된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomState.isActiveSession(roomId, userId, "session-new")).isTrue();
        assertThat(roomState.isActiveSession(roomId, userId, "session-old")).isFalse();
        assertThat(roomState.isActiveSession(roomId, userId, null)).isFalse();
    }

    @Test
    void 더_늦게_열린_세션이_확정된_뒤_옛_세션의_뒤늦은_confirm은_무시된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        Instant oldOpened = Instant.now().minusSeconds(10);
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(confirmOpenedAt(roomId, userId, "session-old", oldOpened)).isEmpty();
        assertThat(roomState.isActiveSession(roomId, userId, "session-new")).isTrue();
    }

    @Test
    void 자리_예약자만_방_토픽을_구독할_수_있다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.hasParticipant(roomId, userId)).isTrue();
        assertThat(roomState.hasParticipant(roomId, 999_999L)).isFalse();
    }

    @Test
    void 재접속_후_도착한_옛_세션의_끊김_이벤트는_무시된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        // 재접속으로 새 세션 확정 → 그 뒤에 옛 세션의 disconnect가 늦게 도착
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomService.handleDisconnect("session-old")).isFalse();

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 유예_중_재구독으로_확정되면_유예가_해제되어_쫓겨나지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        assertThat(roomService.handleDisconnect("session-1")).isTrue();
        assertThat(roomState.getMembers(roomId).getFirst().disconnected())
                .as("유예 중은 disconnected로 실린다")
                .isTrue();

        // join 재호출 없이 STOMP 재구독만으로 복귀하는 경로
        confirm(roomId, userId, "session-2");

        // 유예 해제는 확정 직후에 본다 — cleanupAfter는 리스 없는 task_id(테스트 픽스처)를 끊김으로 되돌린다
        assertThat(roomState.getMembers(roomId).getFirst().disconnected()).isFalse();
        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 유예_복귀와_confirm의_순서가_어느_쪽이든_최종_상태는_확정이다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");

        // 순서 A: HTTP join(복귀) → STOMP confirm
        assertThat(join(userId, code).response().graceRejoin()).isTrue();
        confirm(roomId, userId, "session-2");
        assertThat(roomState.isActiveSession(roomId, userId, "session-2")).isTrue();

        // 순서 B: STOMP confirm이 먼저, 그 뒤 HTTP join — 이미 멤버라 되돌리지 않는다
        roomService.handleDisconnect("session-2");
        confirm(roomId, userId, "session-3");
        assertThat(join(userId, code).response().graceRejoin()).isFalse();
        assertThat(roomState.isActiveSession(roomId, userId, "session-3")).isTrue();
        assertThat(cleanupAfter(31, roomId)).isEmpty();
    }
}
