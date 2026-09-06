package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.common.ErrorCode;
import project.study.common.NotFoundException;
import project.study.room.dto.RoomMember;
import project.study.room.event.ParticipantJoinedEvent;
import project.study.room.service.RoomService;
import project.study.room.service.RoomStateSnapshot;

/** 종료 스냅샷 내보내기·이어받기 (BY-626) — 옛 태스크(old)에서 내보낸 것을 새 태스크(fresh)가 복원한다. */
class RoomServiceSnapshotTest {

    private static final AtomicLong ROOM_IDS = new AtomicLong(1_000_000);

    private static long nextRoomId() {
        return ROOM_IDS.incrementAndGet();
    }

    private RoomService old;
    private RoomService fresh;
    private List<Object> freshEvents;

    @BeforeEach
    void setUp() {
        old = new RoomService("test-secret", 86400, List.of(), event -> {});
        freshEvents = new ArrayList<>();
        fresh = new RoomService("test-secret", 86400, List.of(), freshEvents::add);
    }

    private static RoomService.JoinResult join(RoomService service, Long userId, String code) {
        return service.join(userId, code, "포메" + userId, "목표" + userId, "JOB");
    }

    /** old에 방 하나: 100은 확정+상태 갱신, 200은 예약만. 반환은 초대코드. */
    private String seedOld(long roomId) {
        String code = old.create(1L, roomId).inviteCode();
        join(old, 100L, code);
        old.confirmStomp(roomId, 100L, "old-session-100");
        old.updateCamera(roomId, 100L, true);
        old.updateFocusState(roomId, 100L, "DISTRACTED");
        old.updateStudyTime(roomId, 100L, 1234);
        join(old, 200L, code);
        return code;
    }

    @Test
    void 내보낸_스냅샷을_이어받으면_방_참가자_상태가_그대로_복원된다() {
        long roomId = nextRoomId();
        String code = seedOld(roomId);
        Instant restoredAt = Instant.now();

        int restored = fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        assertThat(restored).isEqualTo(1);
        assertThat(fresh.roomExists(roomId)).isTrue();
        assertThat(fresh.roomExistsByCode(code)).isTrue();
        assertThat(fresh.getRoomIdForUser(100L)).isEqualTo(roomId);
        assertThat(fresh.getRoomIdForUser(200L)).isEqualTo(roomId);
        // 확정됐던 100은 유예 중이라 목록에 보이고, 카메라·집중·공부 초가 마지막 값 그대로다
        RoomMember m100 = fresh.getMembers(roomId).stream()
                .filter(m -> m.userId().equals(100L))
                .findFirst()
                .orElseThrow();
        assertThat(m100.nickname()).isEqualTo("포메100");
        assertThat(m100.cameraOn()).isTrue();
        assertThat(m100.focusState()).isEqualTo("DISTRACTED");
        assertThat(m100.studySeconds()).isEqualTo(1234);
        // 예약만 했던 200은 아직 확정 전이라 목록에 없다
        assertThat(fresh.getMembers(roomId)).extracting(RoomMember::userId).doesNotContain(200L);
    }

    @Test
    void 복원된_참가자는_새_세션으로_구독하면_복귀하고_참여_이력은_중복_발행되지_않는다() {
        long roomId = nextRoomId();
        seedOld(roomId);
        Instant restoredAt = Instant.now();
        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        List<RoomMember> members = fresh.confirmStomp(roomId, 100L, "fresh-session-100");

        assertThat(members).extracting(RoomMember::userId).contains(100L);
        assertThat(fresh.isActiveSession(roomId, 100L, "fresh-session-100")).isTrue();
        // 복귀한 100은 31초 뒤 정리에도 살아남는다 (예약만 했던 200은 예약 TTL로 회수되는 게 정상)
        List<RoomService.AutoLeave> removed = fresh.cleanupExpired(restoredAt.plusSeconds(31));
        assertThat(removed).extracting(RoomService.AutoLeave::userId).doesNotContain(100L);
        assertThat(fresh.isConfirmedMember(roomId, 100L)).isTrue();
        // 옛 태스크에서 이미 확정됐던 참가자라 ParticipantJoined가 다시 나가면 이력이 중복된다
        assertThat(freshEvents).noneMatch(ParticipantJoinedEvent.class::isInstance);
    }

    @Test
    void 복원_후_유예_안에_돌아오지_않은_참가자는_회수된다() {
        long roomId = nextRoomId();
        seedOld(roomId);
        Instant restoredAt = Instant.now();
        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        // 30초 전에는 아무도 안 나간다
        assertThat(fresh.cleanupExpired(restoredAt.plusSeconds(29))).isEmpty();
        List<RoomService.AutoLeave> removed = fresh.cleanupExpired(restoredAt.plusSeconds(31));

        assertThat(removed).extracting(RoomService.AutoLeave::userId).containsExactlyInAnyOrder(100L, 200L);
        // 마지막 사람까지 빠졌으니 방이 소멸한다
        assertThat(fresh.roomExists(roomId)).isFalse();
    }

    @Test
    void 초대코드_묘비도_이어받아_소멸한_방의_코드는_종료_안내로_답한다() {
        long roomId = nextRoomId();
        String code = seedOld(roomId);
        old.leave(roomId, 100L);
        old.leave(roomId, 200L); // 마지막 퇴장 → 방 소멸, 코드 묘비 기록
        Instant restoredAt = Instant.now();
        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        assertThatThrownBy(() -> join(fresh, 300L, code))
                .isInstanceOfSatisfying(
                        NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROOM_CLOSED));
    }

    @Test
    void 이_태스크에_이미_자리_잡은_유저의_스냅샷_자리는_건너뛴다() {
        long oldRoomId = nextRoomId();
        seedOld(oldRoomId);
        // 배포 겹침 구간: 100이 새 태스크에서 새 방에 먼저 입장했다
        long newRoomId = nextRoomId();
        String newCode = fresh.create(9L, newRoomId).inviteCode();
        join(fresh, 100L, newCode);
        Instant restoredAt = Instant.now();

        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        assertThat(fresh.getRoomIdForUser(100L)).isEqualTo(newRoomId);
        assertThat(fresh.hasParticipant(oldRoomId, 100L)).isFalse();
        assertThat(fresh.hasParticipant(oldRoomId, 200L)).isTrue();
    }

    @Test
    void 빈_방도_이어받아_생성_후_10분이_지나면_소멸한다() {
        long roomId = nextRoomId();
        old.create(1L, roomId);
        Instant restoredAt = Instant.now();
        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        assertThat(fresh.roomExists(roomId)).isTrue();
        fresh.cleanupExpired(restoredAt.plusSeconds(601));
        assertThat(fresh.roomExists(roomId)).isFalse();
    }

    @Test
    void 스냅샷은_세션_ID와_끊김_시각을_담지_않는다() {
        long roomId = nextRoomId();
        seedOld(roomId);

        RoomStateSnapshot snapshot = old.exportSnapshot(Instant.now());

        RoomStateSnapshot.ParticipantSnapshot p100 = snapshot.rooms().get(0).participants().stream()
                .filter(p -> p.userId().equals(100L))
                .findFirst()
                .orElseThrow();
        assertThat(p100.stompConfirmed()).isTrue();
        assertThat(p100.firstConfirmedAt()).isNotNull();
        // record에 세션 ID 필드 자체가 없다 — 복원 후 세션은 새로 맺는다
        assertThat(RoomStateSnapshot.ParticipantSnapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("stompSessionId", "disconnectedAt");
    }

    @Test
    void 미확정_예약은_복원_시각부터_예약_TTL을_다시_센다() {
        long roomId = nextRoomId();
        String code = old.create(1L, roomId).inviteCode();
        join(old, 200L, code); // 예약만, 확정 전
        // 스냅샷 시점에 예약이 거의 만료된 상태였다고 가정 — 복원 시각을 예약 29초 뒤로 둔다
        Instant restoredAt = Instant.now().plusSeconds(29);
        fresh.importSnapshot(old.exportSnapshot(restoredAt), restoredAt);

        // 복원 직후 첫 정리 틱(5초)에 회수되면 안 된다
        assertThat(fresh.cleanupExpired(restoredAt.plusSeconds(5))).isEmpty();
        assertThat(fresh.hasParticipant(roomId, 200L)).isTrue();
        // 복원 시각 기준 30초가 지나면 회수된다
        assertThat(fresh.cleanupExpired(restoredAt.plusSeconds(31)))
                .extracting(RoomService.AutoLeave::userId)
                .containsExactly(200L);
    }
}
