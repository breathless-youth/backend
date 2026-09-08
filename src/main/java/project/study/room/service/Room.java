package project.study.room.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import project.study.room.dto.RoomMember;

class Room {
    final Long id;
    final String inviteCode;
    final Instant createdAt;
    final Map<Long, Participant> participants = new HashMap<>();

    Room(Long id, String inviteCode, Instant createdAt) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }

    // 스냅샷·브로드캐스트에 실리는 확정 멤버 목록 — 예약만 한 참가자는 제외
    List<RoomMember> confirmedMembers() {
        return participants.values().stream()
                .filter(p -> p.stompConfirmed)
                .map(p -> new RoomMember(
                        p.userId,
                        p.nickname,
                        p.goal,
                        p.category,
                        p.cameraOn,
                        p.focusState,
                        p.studySeconds,
                        p.disconnectedAt != null))
                .toList();
    }
}
