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

    private String email;
    private String category;
    private String profileImage;
    private String goal;
    private String initial;
    private Integer colorIndex;

    @Enumerated(value = EnumType.STRING)
    private UserStatus status;

    private boolean notifyEnabled;
    private Instant deleteAt;

    public User(Provider provider, String providerUserId) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.status = UserStatus.ACTIVE;
    }

    public User(Provider provider, String providerUserId, String email) {
        this(provider, providerUserId);
        this.email = email;
    }

    public void linkSocialAccount(Provider provider, String providerUserId, String email) {
        if (this.provider != Provider.DEVICE) {
            throw new project.study.common.ConflictException("이미 소셜 계정이 연동된 사용자입니다");
        }
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
    }

    // null이 아닌 필드만 반영한다. initial은 닉네임 첫 글자로 갱신, colorIndex는 최초 배정 후 불변
    public void updateProfile(String nickname, String goal, String category) {
        if (nickname != null) {
            this.nickname = nickname;
            this.initial = nickname.substring(0, 1);
        }
        if (goal != null) {
            this.goal = goal;
        }
        if (category != null) {
            this.category = category;
        }
    }
}
