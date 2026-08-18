package project.study.user.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.user.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    // logout의 TOCTOU 방지 + 소유권 강제 — 호출자 소유의 미사용 토큰만 원자적으로 삭제.
    // 0이면 없거나, 남의 토큰이거나, 그 사이 회전(사용)된 것
    @Modifying
    @Query("delete from RefreshToken t" + " where t.tokenHash = :tokenHash and t.userId = :userId and t.usedAt is null")
    int deleteByTokenHashAndUserIdIfUnused(@Param("tokenHash") String tokenHash, @Param("userId") Long userId);

    // 원자적 사용 처리: 같은 토큰의 동시 재발급 요청 중 정확히 한쪽만 성공하게 한다
    @Modifying
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsedIfUnused(@Param("id") Long id, @Param("now") Instant now);
}
