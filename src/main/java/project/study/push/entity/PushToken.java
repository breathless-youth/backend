package project.study.push.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.common.BaseTimeEntity;

/**
 * 기기 하나의 FCM 등록 토큰. 사용자당 여러 개일 수 있다(소셜 로그인 뒤 여러 기기).
 *
 * <p>쓰기는 PushTokenRepository.upsert 한 곳뿐이라 생성자가 없다. updated_at은 앱이 마지막으로 재등록한 시각이다.
 */
@Entity
@Table(name = "push_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    private PushPlatform platform;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;
}
