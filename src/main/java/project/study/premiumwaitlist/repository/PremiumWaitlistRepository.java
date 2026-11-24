package project.study.premiumwaitlist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.premiumwaitlist.entity.PremiumWaitlist;

public interface PremiumWaitlistRepository extends JpaRepository<PremiumWaitlist, Long> {

    /**
     * 사용자당 한 행으로 저장한다. 같은 사용자의 동시 호출도 unique(user_id)로 직렬화되도록 한 문장으로 처리한다.
     *
     * <ul>
     *   <li>기대 기능은 null로 오면 기존 값을 유지한다.
     *   <li>이메일이 null이면 email·consented_at을 비우고, 같은 이메일이면 consented_at을 그대로 둔다.
     * </ul>
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO premium_waitlist
                (user_id, price, placement, age_over14, email, expected_feature, consented_at, created_at, updated_at)
            VALUES (:userId, :price, :placement, :ageOver14, :email, :expectedFeature,
                    CASE WHEN :email IS NULL THEN NULL ELSE now() END, now(), now())
            ON CONFLICT (user_id) DO UPDATE SET
                price = EXCLUDED.price,
                placement = EXCLUDED.placement,
                age_over14 = EXCLUDED.age_over14,
                email = EXCLUDED.email,
                expected_feature = COALESCE(EXCLUDED.expected_feature, premium_waitlist.expected_feature),
                consented_at = CASE
                    WHEN EXCLUDED.email IS NULL THEN NULL
                    WHEN premium_waitlist.email IS NOT DISTINCT FROM EXCLUDED.email
                         AND premium_waitlist.consented_at IS NOT NULL THEN premium_waitlist.consented_at
                    ELSE EXCLUDED.consented_at
                END,
                updated_at = now()
            """)
    void upsert(
            @Param("userId") long userId,
            @Param("price") int price,
            @Param("placement") String placement,
            @Param("ageOver14") boolean ageOver14,
            @Param("email") String email,
            @Param("expectedFeature") String expectedFeature);
}
