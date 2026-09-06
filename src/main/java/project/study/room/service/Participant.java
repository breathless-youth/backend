package project.study.room.service;

import java.time.Instant;

class Participant {
    // 예약 후 이 시간 안에 STOMP 확정이 없으면 자리를 회수한다
    static final long RESERVATION_TTL_SECONDS = 30;
    // 끊김 후 이 시간 안에 재접속이 없으면 자리를 회수한다
    static final long GRACE_PERIOD_SECONDS = 30;

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

    // 예약 30초 미확정 또는 끊김 30초 유예 만료
    boolean isExpired(Instant now) {
        if (!stompConfirmed && reservedAt.plusSeconds(RESERVATION_TTL_SECONDS).isBefore(now)) {
            return true;
        }
        return disconnectedAt != null
                && disconnectedAt.plusSeconds(GRACE_PERIOD_SECONDS).isBefore(now);
    }
}
