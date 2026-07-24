package project.study.studysession.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "study_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "session_sec")
    private Integer sessionSec;

    @Column(name = "focus_sec")
    private Integer focusSec;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    @OrderBy("startedAt ASC")
    private List<StatusEvent> events = new ArrayList<>();

    // 검증·계산은 StudySessionService.createSessions가 담당한다 — 엔티티는 저장 데이터만 보관
    public StudySession(
            Long userId,
            LocalDate statDate,
            Instant startedAt,
            Instant endedAt,
            int sessionSec,
            int focusSec,
            List<StatusEvent> events) {
        this.userId = userId;
        this.statDate = statDate;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.sessionSec = sessionSec;
        this.focusSec = focusSec;
        this.events = new ArrayList<>(events);
    }
}
