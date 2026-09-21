package project.study.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.user.entity.UserDday;

public interface UserDdayRepository extends JpaRepository<UserDday, Long> {

    Optional<UserDday> findByUserId(Long userId);

    long deleteByUserId(Long userId);
}
