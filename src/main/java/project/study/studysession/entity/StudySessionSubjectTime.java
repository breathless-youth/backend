package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 안에서 과목(·할 일)을 선택한 채 잰 시간 (ADR-0021). 세션의 자식 컬렉션이라 StatusEvent처럼 세션과 함께
 * 저장·삭제되고, 자정 분할 조각마다 비례 배분된 행이 따로 생긴다. taskId가 null이면 과목만 선택해 잰 시간이다.
 */
@Entity
@Table(name = "study_session_subject_time")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySessionSubjectTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    public StudySessionSubjectTime(Long subjectId, Long taskId, int studySec, int focusSec) {
        this.subjectId = subjectId;
        this.taskId = taskId;
        this.studySec = studySec;
        this.focusSec = focusSec;
    }
}
