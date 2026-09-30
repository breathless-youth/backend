package project.study.dday.repository;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.dday.entity.Dday;

public interface DdayRepository extends JpaRepository<Dday, Long> {

    Optional<Dday> findByUserId(Long userId);

    /**
     * UPSERT 한 문장 — 조회-후-저장은 같은 유저의 동시 첫 PUT이 둘 다 insert로 가 유니크 충돌이 500으로 새고,
     * 충돌 뒤 재조회는 트랜잭션이 이미 중단돼 쓸 수 없다 (ActiveStudySessionRepository.upsertSnapshot과 같은 이유).
     */
    @Modifying
    @Query(value = """
                    INSERT INTO user_dday (user_id, title, target_date)
                    VALUES (:userId, :title, :targetDate)
                    ON CONFLICT (user_id) DO UPDATE
                    SET title = excluded.title,
                        target_date = excluded.target_date,
                        updated_at = now()""", nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("title") String title, @Param("targetDate") LocalDate targetDate);

    long deleteByUserId(Long userId);
}
