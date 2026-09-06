package project.study.room.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class Room {
    final Long id;
    // 인메모리 id는 재시작마다 0부터 재사용되므로 DB 이력의 키로는 uid를 쓴다
    final UUID uid = UUID.randomUUID();
    final String inviteCode;
    final Instant createdAt;
    final Map<Long, Participant> participants = new HashMap<>();

    Room(Long id, String inviteCode, Instant createdAt) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }
}
