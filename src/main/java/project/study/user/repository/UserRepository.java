package project.study.user.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.user.entity.Provider;
import project.study.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderUserId(Provider provider, String providerUserId);

    // @Modifying 쿼리의 반환 타입은 void/int/long만 허용된다 — 삽입된 행 수(0 또는 1)를 반환
    // 프로필(자동 닉네임·이니셜·색상)은 등록 시점에 함께 발급한다. 재등록(멱등)이면 기존 값 보존
    // on conflict에 타겟이 없어 닉네임 유니크 충돌도 예외 없이 0행으로 떨어진다
    // (트랜잭션 안에서 제약 위반 예외가 나면 PostgreSQL이 트랜잭션을 abort하고 Hibernate도 세션을 rollback-only로
    // 마킹하므로, 예외를 catch해도 같은 트랜잭션에서 재시도가 불가능하다)
    @Modifying
    @Query(value = """
        insert into users (provider, provider_user_id, nickname, initial, color_index, created_at, updated_at)
        values (:provider, :providerUserId, :nickname, :initial, :colorIndex, now(), now())
        on conflict do nothing
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("provider") String provider,
            @Param("providerUserId") String providerUserId,
            @Param("nickname") String nickname,
            @Param("initial") String initial,
            @Param("colorIndex") int colorIndex);

    boolean existsByNickname(String nickname);

    // 토큰 발급·회전·전량 폐기를 유저 단위로 직렬화하는 뮤텍스(SELECT ... FOR UPDATE). 잠금 없이 폐기하면
    // "지금 커밋 중인" 회전의 새 토큰을 삭제 대상 조회가 못 보고 남긴다 (ADR-0019, Codex P1)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // 일일 리포트 v2 — 이 시각 이후 가입자 (BY-904)
    List<User> findByCreatedAtGreaterThanEqual(Instant from);
}
