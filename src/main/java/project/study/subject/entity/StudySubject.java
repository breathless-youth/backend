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

/** 과목 — 사용자가 공부를 나누는 큰 범위의 카테고리(수학·영어). 시간이 쌓이고 세션 중 선택할 수 있다 (ADR-0021). */
@Entity
@Table(name = "study_subject")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySubject extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String name;

    // soft delete — 세션에 쌓인 시간 기록은 과목을 지워도 남는다
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public StudySubject(Long userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void delete(Instant at) {
        this.deletedAt = at;
    }
}
