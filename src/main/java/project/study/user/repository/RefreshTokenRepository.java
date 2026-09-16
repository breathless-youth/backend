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

    // 잠금 순서용 — 엔티티를 싣지 않고 userId만 본다. 엔티티는 유저 행 잠금을 잡은 뒤 findByTokenHash로
    // 다시 읽어야 잠금 대기 중 바뀐 usedAt·만료 상태를 본다 (영속성 컨텍스트 스냅샷 회피)
    @Query("select t.userId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    // 재사용 감지 시 전량 폐기, 재등록 시 이전 쌍 무효화에 쓴다 (사용 마킹된 tombstone도 함께 지운다).
    // 파생 삭제(엔티티를 전부 로드해 하나씩 remove)가 아니라 한 문장 벌크 삭제 — 회전 이력이 쌓인
    // 유저의 폐기가 유저 행 잠금을 오래 쥐지 않게 (Codex 챌린지 P2). 호출자는 반드시 유저 행 잠금 아래에서 부른다
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    // 원자적 사용 처리: 같은 토큰의 동시 재발급 요청 중 정확히 한쪽만 성공하게 한다
    @Modifying
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsedIfUnused(@Param("id") Long id, @Param("now") Instant now);
}
