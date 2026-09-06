package project.study.room.service;

import java.time.Instant;

class Participant {
    // 만료 후보 인덱스(RoomService.expiryCandidates)가 방을 역참조해 제거할 때 쓴다
    final Long roomId;
    final Long userId;
    // 프로필은 join 시점 값을 보관한다 — 방에 있는 중 프로필을 수정하면 스냅샷에는
    // 낡은 값이 실릴 수 있다 (마지막 값 보관 방식의 알려진 한계)
    String nickname;
    String goal;
    String category;
    boolean cameraOn;
    String focusState;
    int studySeconds;
    Instant reservedAt;
    boolean stompConfirmed;
    Instant disconnectedAt;
    String stompSessionId;
    // 최초 STOMP 확정 시각 — null이면 아직 한 번도 확정된 적 없음.
    // 참여 이력(ParticipantJoined/Left)은 확정된 참가자에 대해서만 기록한다
    Instant firstConfirmedAt;

    Participant(Long roomId, Long userId, String nickname, String goal, String category) {
        this.roomId = roomId;
        this.userId = userId;
        this.nickname = nickname;
        this.goal = goal;
        this.category = category;
        this.cameraOn = false;
        this.focusState = "FOCUS";
        this.studySeconds = 0;
        this.reservedAt = Instant.now();
        this.stompConfirmed = false;
    }
}
