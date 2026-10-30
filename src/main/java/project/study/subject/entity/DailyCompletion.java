package project.study.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "무엇을 어느 날짜에 완료했다" 한 건 (ADR-0038). 날짜는 홈 날짜 줄의 달력 날짜이고 하루는 05시~다음 날 05시(KST)다.
 * 과목 전용이 아니라 대상 종류를 갖는다 — 할 일 반복이 생기면 같은 표를 쓴다. 완료 취소는 행 삭제라 바뀌는 필드가 없다.
 */
@Entity
@Table(name = "daily_completion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private CompletionTarget targetType;

    // 대상 표가 종류마다 달라 FK는 없다. 과목이면 study_subject.id
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "completed_date", nullable = false)
    private LocalDate completedDate;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public DailyCompletion(
            Long userId, CompletionTarget targetType, Long targetId, LocalDate completedDate, Instant completedAt) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.completedDate = completedDate;
        this.completedAt = completedAt;
    }
}
