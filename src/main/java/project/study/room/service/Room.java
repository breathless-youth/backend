package project.study.room.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import project.study.room.dto.RoomMember;

class Room {
    final Long id;
    // 인메모리 id는 재시작마다 0부터 재사용되므로 DB 이력의 키로는 uid를 쓴다
    final UUID uid;
    final String inviteCode;
    final Instant createdAt;
    final Map<Long, Participant> participants = new HashMap<>();

    Room(Long id, String inviteCode, Instant createdAt) {
        this(id, inviteCode, createdAt, UUID.randomUUID());
    }

    // 스냅샷 복원용 — 이력 테이블·이벤트가 쓰는 uid를 그대로 이어받는다
    Room(Long id, String inviteCode, Instant createdAt, UUID uid) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
        this.uid = uid;
    }

    // 스냅샷·브로드캐스트에 실리는 확정 멤버 목록 — 예약만 한 참가자는 제외
    List<RoomMember> confirmedMembers() {
        return participants.values().stream()
                .filter(p -> p.stompConfirmed)
                .map(p -> new RoomMember(
                        p.userId, p.nickname, p.goal, p.category, p.cameraOn, p.focusState, p.studySeconds))
                .toList();
    }
}
