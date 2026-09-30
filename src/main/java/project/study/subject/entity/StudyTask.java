package project.study.subject.entity;

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
import project.study.common.BaseTimeEntity;

/**
 * 할 일 — 과목의 하위 항목. 완료 체크와 시간이 모두 붙는다. 마감일이 없어 완료 전까지 남고,
 * 완료 시각이 오늘(KST)인 것만 목록에 보인다 (ADR-0021).
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
