package project.study.user.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
    // (트랜잭션 안에서 제약 위반 예외가 나면 PostgreSQL이 트랜잭션을 abort해 재시도가 불가능하다)
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

    // 기간별 가입 수 — Between은 양끝 포함이라 하루 경계에서 다음날 00:00:00.000을 삼킨다.
    // 반개구간 [from, to)로 세어 날짜 간 중복 집계를 막는다
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    // 기간별 가입 유저 목록(가입 시각 오름차순) — 일일 리포트의 신규 가입 상세용. 반개구간 [from, to)
    List<User> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAt(Instant from, Instant to);
}
