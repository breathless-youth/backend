package project.study.dday.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.dday.entity.Dday;

public interface DdayRepository extends JpaRepository<Dday, Long> {

    Optional<Dday> findByUserId(Long userId);

    long deleteByUserId(Long userId);
}
