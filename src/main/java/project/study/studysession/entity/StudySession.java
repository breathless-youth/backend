package project.study.studysession.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private LocalDate statDate;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer sessionSec;
    private Integer focusSec;
}
