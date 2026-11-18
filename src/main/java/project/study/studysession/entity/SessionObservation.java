package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 세션 조각의 관찰 기록 — study_session_observation 한 행. 순공 계산에 쓰지 않고 세션과 함께 저장·대체·삭제된다
 * (완료 할 일처럼 값 컬렉션). endedAt이 null이면 길이가 없는 순간 기록이다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionObservation {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ObservationType type;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public SessionObservation(ObservationType type, Instant startedAt, Instant endedAt) {
        this.type = type;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }
}
