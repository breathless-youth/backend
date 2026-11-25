package project.study.premiumwaitlist.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프리미엄 사전 신청 1건 (BY-1394). 쓰기는 {@code PremiumWaitlistRepository#upsert}가 하고, 이 엔티티는 조회·검증용이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PremiumWaitlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private int price;

    private String placement;

    private boolean ageOver14;

    /** 14세 이상이 보낸 때만 평문으로 저장한다. 안 보냈거나 14세 미만이거나 파기했으면 null. */
    private String email;

    private String expectedFeature;

    /** 이메일을 저장한 시각. 이메일이 바뀌면 갱신하고, 이메일이 없으면 null. */
    private Instant consentedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
