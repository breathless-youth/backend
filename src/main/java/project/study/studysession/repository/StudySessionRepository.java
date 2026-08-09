package project.study.studysession.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.dto.HeavyUser;
import project.study.studysession.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    // 순공시간(focusSec)이 minFocusSec 이상인 세션만 조회 — 짧은 세션은 저장은 되어도 조회엔 보이지 않는다
    List<StudySession> findByUserIdAndStatDateBetweenAndFocusSecGreaterThanEqualOrderByStartedAtDesc(
            Long userId, LocalDate from, LocalDate to, int minFocusSec);

    // 멱등 재제출 판별·응답용 — 루트 제출 시각이 같은 조각 세션들(자정 분할 포함). 분할 조각의
    // started_at(자정)은 루트가 아니므로 별개 제출의 멱등 키와 혼동되지 않는다
    List<StudySession> findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(Long userId, Instant submissionStartedAt);

    // dev 목데이터 시더가 재시작마다 데모 유저의 세션을 갈아끼울 때 사용
    void deleteByUserId(Long userId);

    // 스트릭 계산용 — 세션 하나라도 focusSec이 minFocusSec 이상인 날짜 목록 (중복 없음, 최신순)
    @Query("""
            select distinct s.statDate
            from StudySession s
            where s.userId = :userId and s.focusSec >= :minFocusSec
            order by s.statDate desc""")
    List<LocalDate> findDistinctStatDates(@Param("userId") Long userId, @Param("minFocusSec") int minFocusSec);

    // 특정 기간 동안 세션 하나라도 focusSec이 minFocusSec 이상인 날짜 목록 (중복 없음, 오름차순).
    // 일간 조회의 달력 표시(1분 기준)와 스트릭 기간 조회(10분 기준) 양쪽에서 임계값만 다르게 재사용한다
    @Query("""
            select distinct s.statDate
            from StudySession s
            where s.userId = :userId and s.statDate between :from and :to and s.focusSec >= :minFocusSec
            order by s.statDate""")
    List<LocalDate> findDistinctStatDatesBetween(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec);

    // 헤비유저 조회 — 구간 안에서 스트릭 인정 기준(focusSec >= minFocusSec)을 만족한 날이
    // minActiveDays 이상인 유저. 스트릭과 같은 세션 단위 판정이라 자정 분할 조각을 병합하지 않는다
    @Query("""
            select new project.study.studysession.dto.HeavyUser(s.userId, count(distinct s.statDate))
            from StudySession s
            where s.statDate between :from and :to and s.focusSec >= :minFocusSec
            group by s.userId
            having count(distinct s.statDate) >= :minActiveDays
            order by count(distinct s.statDate) desc, s.userId""")
    List<HeavyUser> findHeavyUsers(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec,
            @Param("minActiveDays") long minActiveDays);
}
