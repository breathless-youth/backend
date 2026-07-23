package project.study.studysession.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.studysession.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    List<StudySession> findByUserIdAndStatDateBetweenOrderByStartedAtDesc(Long userId, LocalDate from, LocalDate to);
}
