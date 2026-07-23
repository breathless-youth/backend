package project.study.studysession.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.dto.EventStatusCount;
import project.study.studysession.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    List<StudySession> findByUserIdAndStatDateBetweenOrderByStartedAtDesc(Long userId, LocalDate from, LocalDate to);

    @Query("""
            select new project.study.studysession.dto.EventStatusCount(e.status, count(e))
            from StudySession s join s.events e
            where s.userId = :userId and s.statDate between :from and :to
            group by e.status""")
    List<EventStatusCount> countEventsByStatus(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
