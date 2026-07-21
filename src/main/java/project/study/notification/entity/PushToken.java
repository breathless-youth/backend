package project.study.notification.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.user.entity.User;

@Table(
        indexes = {
            @Index(name = "push_token", columnList = "token", unique = true),
            @Index(name = "push_token", columnList = "user_id")
        })
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String token;
    private String platform;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
