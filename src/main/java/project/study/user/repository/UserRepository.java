package project.study.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.user.entity.Provider;
import project.study.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndProviderUserId(Provider provider, String providerUserId);
}
