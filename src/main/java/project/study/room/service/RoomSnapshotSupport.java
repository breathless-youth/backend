package project.study.room.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RoomService} 메모리 상태의 스냅샷 내보내기·이어받기 (BY-626).
 *
 * <p>RoomService의 내부 컬렉션을 그대로 공유하며, 호출은 항상 RoomService의 글로벌 락 안에서만 일어난다 —
 * 동기화를 따로 하지 않는다. I/O도 없다(읽기·쓰기는 {@code room.snapshot} 패키지가 락 밖에서 한다).
 * RoomService.java가 checkstyle FileLength(400줄)에 걸려 이쪽으로 뺐다.
 *
 * <p>이어받기 규칙: 복원한 참가자는 전부 "복원 시각에 끊김(유예 시작)"으로 넣는다. 재접속한 SUBSCRIBE가
 * confirmStomp에서 "유예 중 + 새 세션 = 복귀"로 처리되고, 30초 안에 안 돌아오면 cleanupExpired가 회수한다 —
 * 기존 규칙에 새 분기를 더하지 않는다. stompConfirmed·firstConfirmedAt은 그대로 두어 유예 중 멤버가 스냅샷
 * 목록에 보이고 참여 이력 이벤트가 중복 발행되지 않게 한다. 배포 겹침 구간에 이 태스크가 이미 만든 방(코드)이나
 * 이미 자리 잡은 유저는 새 상태를 우선해 스냅샷 쪽을 건너뛴다. 방 ID는 DB 시퀀스라 겹치지 않는다.
 */
final class RoomSnapshotSupport {

    private static final Logger log = LoggerFactory.getLogger(RoomSnapshotSupport.class);

    private final Map<Long, Room> roomById;
    private final Map<String, Room> roomByCode;
    private final Map<Long, Long> userToRoomId;
    private final Set<Participant> expiryCandidates;
    private final Map<Long, Room> emptyRooms;
    private final ClosedInviteCodes closedCodes;

    RoomSnapshotSupport(
            Map<Long, Room> roomById,
            Map<String, Room> roomByCode,
            Map<Long, Long> userToRoomId,
            Set<Participant> expiryCandidates,
            Map<Long, Room> emptyRooms,
            ClosedInviteCodes closedCodes) {
        this.roomById = roomById;
        this.roomByCode = roomByCode;
        this.userToRoomId = userToRoomId;
        this.expiryCandidates = expiryCandidates;
        this.emptyRooms = emptyRooms;
        this.closedCodes = closedCodes;
    }

    RoomStateSnapshot export(Instant now) {
        List<RoomStateSnapshot.RoomSnapshot> rooms = roomById.values().stream()
                .map(room -> new RoomStateSnapshot.RoomSnapshot(
                        room.id,
                        room.uid,
                        room.inviteCode,
                        room.createdAt,
                        room.participants.values().stream()
                                .map(RoomSnapshotSupport::toSnapshot)
                                .toList()))
                .toList();
        return new RoomStateSnapshot(now, rooms, closedCodes.entries());
    }

    /** @return 복원한 방 수 */
    int restore(RoomStateSnapshot snapshot, Instant now) {
        int restored = 0;
        for (RoomStateSnapshot.RoomSnapshot rs : snapshot.rooms()) {
            if (roomById.containsKey(rs.id()) || roomByCode.containsKey(rs.inviteCode())) {
                log.warn("스냅샷 방 건너뜀(이미 존재): roomId={}, inviteCode={}", rs.id(), rs.inviteCode());
                continue;
            }
            Room room = new Room(rs.id(), rs.inviteCode(), rs.createdAt(), rs.uid());
            for (RoomStateSnapshot.ParticipantSnapshot ps : rs.participants()) {
                if (userToRoomId.containsKey(ps.userId())) {
                    log.warn("스냅샷 참가자 건너뜀(이미 다른 방에 자리): userId={}, roomId={}", ps.userId(), rs.id());
                    continue;
                }
                Participant p = toParticipant(room.id, ps, now);
                room.participants.put(p.userId, p);
                userToRoomId.put(p.userId, room.id);
                expiryCandidates.add(p);
            }
            roomById.put(room.id, room);
            roomByCode.put(room.inviteCode, room);
            if (room.participants.isEmpty()) {
                emptyRooms.put(room.id, room);
            }
            restored++;
        }
        snapshot.closedCodes().forEach(closedCodes::record);
        log.info("룸 스냅샷 복원: 방 {}개 (스냅샷 시각 {})", restored, snapshot.takenAt());
        return restored;
    }

    private static RoomStateSnapshot.ParticipantSnapshot toSnapshot(Participant p) {
        return new RoomStateSnapshot.ParticipantSnapshot(
                p.userId,
                p.nickname,
                p.goal,
                p.category,
                p.cameraOn,
                p.focusState,
                p.studySeconds,
                p.reservedAt,
                p.stompConfirmed,
                p.firstConfirmedAt);
    }

    private static Participant toParticipant(Long roomId, RoomStateSnapshot.ParticipantSnapshot ps, Instant now) {
        Participant p = new Participant(roomId, ps.userId(), ps.nickname(), ps.goal(), ps.category());
        p.cameraOn = ps.cameraOn();
        p.focusState = ps.focusState();
        p.studySeconds = ps.studySeconds();
        // 미확정 예약은 복원 시각부터 TTL을 다시 센다 — 옛 시각을 물려받으면 복원 직후 첫 정리 틱에 회수될 수 있다
        p.reservedAt = ps.stompConfirmed() ? ps.reservedAt() : now;
        p.stompConfirmed = ps.stompConfirmed();
        p.firstConfirmedAt = ps.firstConfirmedAt();
        p.disconnectedAt = now; // 복원 시각에 끊김 — 재접속 SUBSCRIBE가 복귀로, 미복귀는 유예 만료로 처리된다
        p.stompSessionId = null;
        return p;
    }
}
