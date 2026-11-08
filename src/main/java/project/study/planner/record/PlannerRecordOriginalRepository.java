package project.study.planner.record;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.entity.StudySession;

interface PlannerRecordOriginalRepository extends JpaRepository<StudySession, Long> {

    List<StudySession> findByUserIdOrderByStartedAtAsc(Long userId);

    @Query("""
            select s from StudySession s where s.userId = :userId
            and (s.submissionStartedAt in :roots or s.id in :ids) order by s.startedAt, s.id
            """)
    List<StudySession> forRecordKeys(
            @Param("userId") Long userId, @Param("roots") List<Instant> roots, @Param("ids") List<Long> ids);

    @Query(value = """
            SELECT s.* FROM study_session s WHERE s.user_id = :userId AND EXISTS (
                SELECT 1 FROM study_session p WHERE p.user_id = s.user_id AND p.source = s.source
                  AND ((s.submission_started_at IS NOT NULL AND p.submission_started_at = s.submission_started_at)
                       OR (s.submission_started_at IS NULL AND p.id = s.id))
                  AND p.stat_date BETWEEN :from AND :to
            ) ORDER BY s.started_at, s.id
            """, nativeQuery = true)
    List<StudySession> inStatDates(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 닿는 조각만이 아니라 묶음 전체를 읽어 날짜가 달라도 키와 이벤트 기준이 같다.
    // 종료 시각 없는 이전 기록은 study_sec로 복원한다.
    @Query(value = """
            SELECT s.* FROM study_session s WHERE s.user_id = :userId AND EXISTS (
                SELECT 1 FROM study_session p WHERE p.user_id = s.user_id AND p.source = s.source
                  AND ((s.submission_started_at IS NOT NULL AND p.submission_started_at = s.submission_started_at)
                       OR (s.submission_started_at IS NULL AND p.id = s.id))
                  AND p.started_at < :to
                  AND COALESCE(p.ended_at, p.started_at + COALESCE(p.study_sec,0) * interval '1 second') > :from
            ) ORDER BY s.started_at, s.id
            """, nativeQuery = true)
    List<StudySession> inWindow(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
