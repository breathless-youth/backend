package project.study.dday.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.common.BaseTimeEntity;

/** 홈 좌상단 D-Day — 사용자가 정한 목표일과 제목. 유저당 1개이며 남은 일수는 클라이언트가 기기 날짜로 센다. */
@Entity
@Table(name = "user_dday")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dday extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 유저당 1개 — 여러 개로 늘릴 때 유니크를 풀고 대표 플래그를 더한다
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String title;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    public Dday(Long userId, String title, LocalDate targetDate) {
        this.userId = userId;
        this.title = title;
        this.targetDate = targetDate;
    }

    public void update(String title, LocalDate targetDate) {
        this.title = title;
        this.targetDate = targetDate;
    }
}
