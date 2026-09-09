package project.study.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방의 자리 하나 = 행 하나. 예약 → STOMP 확정 → (끊김 유예) → 퇴장의 전이가 컬럼으로 표현된다.
 * left_at이 NULL이면 지금 자리를 가진 참가자, 아니면 이력. joined_at이 NULL이면 확정된 적 없는 예약이라
 * 만료 시 이력 없이 삭제된다. 스키마 검증·테스트 조회 전용 (운영 경로는 RoomParticipationRepository의 SQL).
 */
@Table(name = "room_participations")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private Long userId;

    private String nickname;
    private String goal;
    private String category;

    @Column(nullable = false)
    private boolean cameraOn;

    @Column(nullable = false, length = 20)
    private String focusState;

    @Column(nullable = false)
    private int focusSec;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(nullable = false)
    private boolean stompConfirmed;

    private String stompSessionId;
    private Instant sessionOpenedAt;
    private String taskId;
    private Instant disconnectedAt;
    private Instant joinedAt;
    private Instant leftAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LeaveReason leaveReason;
}
