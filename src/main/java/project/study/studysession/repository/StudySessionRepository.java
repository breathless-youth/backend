package project.study.studysession.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    List<StudySession> findByUserIdAndStatDateBetweenOrderByStartedAtDesc(Long userId, LocalDate from, LocalDate to);

    // dev 목데이터 시더가 재시작마다 데모 유저의 세션을 갈아끼울 때 사용
    void deleteByUserId(Long userId);

    // 스트릭 계산용 — 유저의 공부한 날짜 목록 (중복 없음, 최신순)
    @Query("""
            select distinct s.statDate
            from StudySession s
            where s.userId = :userId
            order by s.statDate desc""")
    List<LocalDate> findDistinctStatDates(@Param("userId") Long userId);

    // 일간 조회 시 달력 표시용 — 특정 기간(보통 한 달) 동안 공부한 날짜 목록 (중복 없음, 오름차순)
    @Query("""
            select distinct s.statDate
            from StudySession s
            where s.userId = :userId and s.statDate between :from and :to
            order by s.statDate""")
    List<LocalDate> findDistinctStatDatesBetween(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
