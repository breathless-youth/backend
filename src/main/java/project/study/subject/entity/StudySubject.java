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
import org.hibernate.annotations.DynamicUpdate;
import project.study.common.BaseTimeEntity;

/** 과목 — 사용자가 공부를 나누는 큰 범위의 카테고리(수학·영어). 시간이 쌓이고 세션 중 선택할 수 있다 (ADR-0021). */
@Entity
@Table(name = "study_subject")
// 바뀐 컬럼만 UPDATE한다 — 순서 저장이 읽어 둔 엔티티를 flush할 때 그 사이 다른 요청이 쓴 deleted_at·name을
// 옛 값으로 덮어(삭제된 과목이 되살아남) 쓰지 않게 (ADR-0022)
@DynamicUpdate
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

    // 목록 정렬 — 사용자별 0부터. 순서 저장이 0..n-1로 다시 매기고 새 과목은 살아있는 max+1이라 빈자리가 있어도 된다 (ADR-0022)
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // 색 팔레트 인덱스(0..19) — 생성 시 서비스가 배정하고 이후 바뀌지 않는다. 화면은 이 값을 팔레트에 매핑만 한다
    @Column(name = "color_index", nullable = false)
    private int colorIndex;

    // soft delete — 세션에 쌓인 시간 기록은 과목을 지워도 남는다
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public StudySubject(Long userId, String name, int sortOrder, int colorIndex) {
        this.userId = userId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.colorIndex = colorIndex;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 순서 저장 전용 — 서비스가 전체 순서를 다시 매길 때만 부른다. */
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void delete(Instant at) {
        this.deletedAt = at;
    }
}
