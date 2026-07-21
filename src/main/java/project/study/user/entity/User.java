package project.study.user.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Table(
        name = "users",
        indexes = {@Index(name = "users", columnList = "provider, provider_user_id", unique = true)})
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(value = EnumType.STRING)
    private Provider provider;

    private String providerUserId;

    @Column(unique = true)
    private String nickname;

    private String category;
    private String profileImage;

    @Enumerated(value = EnumType.STRING)
    private UserStatus status;

    private boolean notifyEnabled;
    private LocalDateTime deleteAt;

    public User(Provider provider, String providerUserId) {
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
