package project.study.studysession.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // (userId, startedAt)이 멱등 키다 — 재전송 중복 저장은 DB 유니크 제약(uq_study_session_user_started_at)이 막는다
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    // 루트 제출의 시작 시각 — 자정 분할 조각들이 같은 값을 공유해 재제출 판별·응답 조회의 기준이 된다.
    // NULL은 V7 이전 레거시 행(루트 복원 불가) — 재제출 판별에서 제외되고 유니크 제약이 충돌을 막는다
    @Column(name = "submission_started_at")
    private Instant submissionStartedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "study_sec")
    private Integer studySec;

    @Column(name = "focus_sec")
    private Integer focusSec;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    @OrderBy("startedAt ASC")
    private List<StatusEvent> events = new ArrayList<>();

    // 과목 구간 (ADR-0023) — 이벤트와 같은 자식 컬렉션. 자정 분할 조각마다 잘린 행이 생기고 파생값은 서버가 계산한다
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    @OrderBy("startedAt ASC")
    private List<StudySessionSubjectSegment> subjectSegments = new ArrayList<>();

    // 세션 제출 시 완료한 할 일 (ADR-0022) — (session_id, task_id)가 PK인 값 컬렉션이라 엔티티 대신 값으로 매핑한다.
    // 자정 분할이면 완료 시각이 속한 조각 하나에만 붙는다. 세션과 함께 저장·대체·삭제된다
    @ElementCollection
    @CollectionTable(name = "study_session_task_done", joinColumns = @JoinColumn(name = "session_id", nullable = false))
    @Column(name = "task_id", nullable = false)
    private Set<Long> completedTaskIds = new HashSet<>();

    // BY-447: 자동 확정본 표시 — true인 세션은 잠정 기록이라 늦은 최종 제출·재확정이 대체할 수 있다
    @Column(name = "auto_finalized", nullable = false)
    private boolean autoFinalized;

    // BY-455: 복구 확인 시각 — 복구 판별 API가 이 세션을 사용자에게 보여준 시점. NULL이면 미확인
    @Column(name = "recovery_acknowledged_at")
    private Instant recoveryAcknowledgedAt;

    // 검증·계산은 StudySessionService.validateAndBuildSessions가 담당한다 — 엔티티는 저장 데이터만 보관
    public StudySession(
            Long userId,
            LocalDate statDate,
            Instant startedAt,
            Instant endedAt,
            int studySec,
            int focusSec,
            List<StatusEvent> events) {
        this.userId = userId;
        this.submissionStartedAt = startedAt;
        this.statDate = statDate;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.studySec = studySec;
        this.focusSec = focusSec;
        this.events = new ArrayList<>(events);
    }

    /** 자정 분할 조각을 루트 제출에 귀속시킨다 — 분할 직후 서비스만 호출한다 (기본값은 자신의 시작 시각 = 단독 세션). */
    public void attachToSubmission(Instant submissionStartedAt) {
        this.submissionStartedAt = submissionStartedAt;
    }

    /** 조각별로 잘라 계산한 과목 구간을 붙인다 — 분할 직후 서비스만 호출한다. */
    public void attachSubjectSegments(List<StudySessionSubjectSegment> subjectSegments) {
        this.subjectSegments = new ArrayList<>(subjectSegments);
    }

    /** 조각별로 귀속된 완료 할 일을 붙인다 — 분할 직후 서비스만 호출한다. */
    public void attachCompletedTasks(Collection<Long> taskIds) {
        this.completedTaskIds = new HashSet<>(taskIds);
    }

    /** 확정 스케줄러가 만든 세션임을 표시한다 — 저장 직전 서비스만 호출한다. */
    public void markAutoFinalized() {
        this.autoFinalized = true;
    }
}
