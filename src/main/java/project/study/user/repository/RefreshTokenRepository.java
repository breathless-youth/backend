package project.study.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.user.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    void deleteByTokenHash(String tokenHash);
}
