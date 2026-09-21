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
 * 세션 안에서 과목을 선택한 채 잰 시간 (ADR-0021). 세션의 자식 컬렉션이라 StatusEvent처럼 세션과 함께
 * 저장·삭제되고, 자정 분할 조각마다 비례 배분된 행이 따로 생긴다.
 *
 * <p>측정 단위는 과목이다. 할 일은 체크리스트일 뿐 시간이 붙지 않는다.
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

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    public StudySessionSubjectTime(Long subjectId, int studySec, int focusSec) {
        this.subjectId = subjectId;
        this.studySec = studySec;
        this.focusSec = focusSec;
    }
}
