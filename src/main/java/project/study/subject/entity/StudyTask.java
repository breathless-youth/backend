package project.study.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.common.BaseTimeEntity;

/**
 * 날짜별 계획의 할 일. id와 실제 완료 시각을 보존한다 (ADR-0044).
 * plannedDate/seriesId는 플래너 저장소가 관리하고 기존 세션 API는 읽기 메타데이터로 사용한다.
 */
@Entity
@Table(name = "study_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyTask extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "planned_date", insertable = false, updatable = false)
    private LocalDate plannedDate;

    @Column(name = "series_id", insertable = false, updatable = false)
    private Long seriesId;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public StudyTask(Long subjectId, String name) {
        this.subjectId = subjectId;
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void markDone(Instant at) {
        this.doneAt = at;
    }

    public void clearDone() {
        this.doneAt = null;
    }

    public void delete(Instant at) {
        this.deletedAt = at;
    }
}
