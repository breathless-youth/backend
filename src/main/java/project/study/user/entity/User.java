package project.study.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.common.BaseTimeEntity;

@Table(
        name = "users",
        indexes = {
            @Index(
                    name = "idx_users_provider_provider_user_id",
                    columnList = "provider, provider_user_id",
                    unique = true)
        })
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private String providerUserId;

    @Column(unique = true)
    private String nickname;

    private String category;
    private String profileImage;

    @Enumerated(value = EnumType.STRING)
    private UserStatus status;

    private boolean notifyEnabled;
    private Instant deleteAt;

    public User(Provider provider, String providerUserId) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.status = UserStatus.ACTIVE;
    }

    public void linkSocialAccount(Provider provider, String providerUserId) {
        if (this.provider != Provider.DEVICE) {
            throw new project.study.common.ConflictException("이미 소셜 계정이 연동된 사용자입니다");
        }
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
