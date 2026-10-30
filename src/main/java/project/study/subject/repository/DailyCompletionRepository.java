package project.study.subject.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.subject.entity.CompletionTarget;
import project.study.subject.entity.DailyCompletion;

/** 날짜별 완료 기록 (ADR-0038) — 셋 다 유니크 인덱스 (user_id, completed_date, target_type, target_id)를 탄다. */
public interface DailyCompletionRepository extends JpaRepository<DailyCompletion, Long> {

    List<DailyCompletion> findByUserIdAndCompletedDateAndTargetType(
            Long userId, LocalDate completedDate, CompletionTarget targetType);

    boolean existsByUserIdAndCompletedDateAndTargetTypeAndTargetId(
            Long userId, LocalDate completedDate, CompletionTarget targetType, Long targetId);

    // 완료 취소 — 없으면 0건 지우고 끝난다(멱등). 파생 deleteBy는 읽어서 remove하므로 같은 카드를 빠르게 두 번 밀면
    // 뒤 요청이 사라진 행을 지우려다 StaleState(500)가 난다 — 벌크 한 문장이면 0건이어도 그냥 끝난다
    @Modifying
    @Query("""
            delete from DailyCompletion c
            where c.userId = :userId and c.completedDate = :date and c.targetType = :type and c.targetId = :targetId""")
    int deleteCompletion(
            @Param("userId") Long userId,
            @Param("date") LocalDate date,
            @Param("type") CompletionTarget type,
            @Param("targetId") Long targetId);
}
