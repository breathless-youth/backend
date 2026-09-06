package project.study.room.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 종료 시점의 룸 메모리 상태 (BY-626). 새 태스크가 이어받을 수 있는 것만 담는다.
 *
 * <p>STOMP 세션 ID와 끊김 시각은 담지 않는다 — 세션은 죽는 태스크의 것이라 의미가 없고, 복원한 참가자는
 * "복원 시각에 끊김(유예 시작)"으로 새로 찍어 기존 confirmStomp/cleanupExpired 규칙이 그대로 이어받게 한다.
 * 카메라·집중·공부 초는 프론트가 재연결 시 다시 보내기 전까지 다른 멤버 화면에 보일 마지막 값이다.
 */
public record RoomStateSnapshot(Instant takenAt, List<RoomSnapshot> rooms, Map<String, Instant> closedCodes) {

    public record RoomSnapshot(
            Long id, UUID uid, String inviteCode, Instant createdAt, List<ParticipantSnapshot> participants) {}

    public record ParticipantSnapshot(
            Long userId,
            String nickname,
            String goal,
            String category,
            boolean cameraOn,
            String focusState,
            int studySeconds,
            Instant reservedAt,
            boolean stompConfirmed,
            Instant firstConfirmedAt) {}
}
