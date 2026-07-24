package project.study.user.repository;

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
    @Modifying
    @Query(value = """
        insert into users (provider, provider_user_id, created_at, updated_at)
        values (:provider, :providerUserId, now(), now())
        on conflict (provider, provider_user_id) do nothing
        """, nativeQuery = true)
    int insertIfAbsent(@Param("provider") String provider, @Param("providerUserId") String providerUserId);
}
