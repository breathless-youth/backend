package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 안에서 과목을 선택한 채 공부한 구간 [startedAt, endedAt) (ADR-0023). 세션의 자식 컬렉션이라 StatusEvent처럼
 * 세션과 함께 저장·삭제되고, 자정 분할 조각마다 잘린 행이 따로 생긴다.
 *
 * <p>studySec·focusSec는 서버가 구간과 이벤트로 계산한 파생값이다 — 앱 타이머가 멈추는 규칙과 같이
 * PAUSE 겹침은 둘 다에서, 다른 이벤트 겹침은 순공에서만 빠진다. 과목 누적이 subject_id 합산 한 문장이 되도록 행에 둔다.
 */
@Entity
@Table(name = "study_session_subject_segment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySessionSubjectSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    public StudySessionSubjectSegment(Long subjectId, Instant startedAt, Instant endedAt, int studySec, int focusSec) {
        this.subjectId = subjectId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.studySec = studySec;
        this.focusSec = focusSec;
    }
}
