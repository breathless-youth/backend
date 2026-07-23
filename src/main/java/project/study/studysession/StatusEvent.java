package project.study.studysession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "status_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public StatusEvent(EventStatus status, Instant startedAt, Instant endedAt) {
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public long durationSec() {
        return Duration.between(startedAt, endedAt).toSeconds();
    }
}
