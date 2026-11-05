package project.study.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.common.BaseTimeEntity;
import project.study.common.time.DefaultTimeZone;
import project.study.user.nickname.Nicknames;
import project.study.user.service.AlreadyLinkedException;

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

    /** 가입 때 받은 기본 닉네임을 그대로 쓰고 있는가 — 사용자가 다른 닉네임으로 바꾸면 false (BY-1135). */
    @Column(nullable = false)
    private boolean nicknameAuto;

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

    @Column(nullable = false)
    private String timeZone = DefaultTimeZone.ZONE.getId();

    public User(Provider provider, String providerUserId) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.status = UserStatus.ACTIVE;
    }

    public User(Provider provider, String providerUserId, String email) {
        this(provider, providerUserId);
        this.email = email;
    }

    // 전환: 식별자 쌍을 바꾼다. userId가 그대로라 기록이 이어지고, 옛 (DEVICE, deviceId) 연결은 끊겨 그 기기는 재등록하면 새 비회원이 된다
    public void linkSocialAccount(Provider provider, String providerUserId, String email) {
        if (this.provider != Provider.DEVICE) {
            throw new AlreadyLinkedException();
        }
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
    }

    // null이 아닌 필드만 반영한다. initial은 닉네임 첫 글자로 갱신, colorIndex는 최초 배정 후 불변.
    // 지금 닉네임을 그대로 다시 보내면(앱이 폼 전체를 보낼 때) 바꾼 것으로 보지 않는다 — 기본 닉네임 표시가 꺼지지 않게
    public void updateProfile(String nickname, String goal, String category) {
        if (nickname != null && !nickname.equals(this.nickname)) {
            this.nickname = nickname;
            this.initial = Nicknames.initialOf(nickname); // 첫 글자 단위 — 이모지가 앞에 와도 안 깨진다
            this.nicknameAuto = false;
        }
        if (goal != null) {
            this.goal = goal;
        }
        if (category != null) {
            this.category = category;
        }
    }

    public void changeTimeZone(ZoneId zone) {
        this.timeZone = zone.getId();
    }

    // 이전 키를 돌려준다 — 호출자가 커밋 뒤 그 파일을 지운다
    public String replaceProfileImage(String key) {
        String previous = this.profileImage;
        this.profileImage = key;
        return previous;
    }
}
