package project.study.push.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.push.entity.PushToken;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    /**
     * UPSERT 한 문장 — 같은 토큰이면 주인·권한 상태·갱신 시각을 덮어쓴다. 엔티티 저장은 값이 같으면 UPDATE를 건너뛰어
     * updated_at(마지막 사용 시각)이 멈추고, 조회-후-저장은 동시 등록에서 유니크 충돌이 난다 (DdayRepository.upsert와 같은 이유).
     */
    @Modifying
    @Query(value = """
                    INSERT INTO push_token (user_id, token, platform, notifications_enabled, created_at, updated_at)
                    VALUES (:userId, :token, :platform, :notificationsEnabled, now(), now())
                    ON CONFLICT (token) DO UPDATE
                    SET user_id = excluded.user_id,
                        platform = excluded.platform,
                        notifications_enabled = excluded.notifications_enabled,
                        updated_at = now()""", nativeQuery = true)
    void upsert(
            @Param("userId") Long userId,
            @Param("token") String token,
            @Param("platform") String platform,
            @Param("notificationsEnabled") boolean notificationsEnabled);

    @Query("SELECT t.token FROM PushToken t WHERE t.userId IN :userIds")
    List<String> findTokensByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /** 발송 결과 처리에서 부른다 — 발송은 트랜잭션 밖(네트워크)이라 삭제가 스스로 트랜잭션을 연다. */
    @Transactional
    @Modifying
    @Query("DELETE FROM PushToken t WHERE t.token IN :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
