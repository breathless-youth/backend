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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.room.event.LeaveReason;

@Table(name = "room_participations")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID roomUid;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt;

    private Instant leftAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LeaveReason leaveReason;

    public RoomParticipation(UUID roomUid, Long userId, Instant joinedAt) {
        this.roomUid = roomUid;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }

    public void close(Instant leftAt, LeaveReason reason) {
        this.leftAt = leftAt;
        this.leaveReason = reason;
    }
}
