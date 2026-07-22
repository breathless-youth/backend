package project.study.studysession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.user.entity.User;

@Table(indexes = {@Index(name = "study_session", columnList = "user_id, stat_date")})
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User userId;

    // statDate는 "한국 기준 무슨 날짜의 공부인가"라는 날짜 개념이므로 LocalDate 유지
    private LocalDate statDate;
    private Instant startedAt;
    private Instant endedAt;
    private Integer sessionSec;
    private Integer focusSec;
}
