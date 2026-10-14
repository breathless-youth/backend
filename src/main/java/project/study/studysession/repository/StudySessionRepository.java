package project.study.studysession.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    // 기간 안 세션 목록(최신순) — 순공시간(focusSec)이 minFocusSec 이상인 세션만. 짧은 세션은 저장은 되어도 조회엔 보이지 않는다
    @Query("""
            select s
            from StudySession s
            where s.userId = :userId and s.statDate between :from and :to and s.focusSec >= :minFocusSec
            order by s.startedAt desc""")
    List<StudySession> findInPeriodWithMinFocusSec(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec);

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

    // 누적 공부일 — 세션 하나라도 focusSec이 minFocusSec 이상인 날짜 수(today까지). 자정을 걸친 세션은
    // 분할 저장되어 stat_date가 둘이므로 distinct가 그대로 이틀로 센다 (BY-645)
    @Query("""
            select count(distinct s.statDate)
            from StudySession s
            where s.userId = :userId and s.focusSec >= :minFocusSec and s.statDate <= :today""")
    long countDistinctStatDates(
            @Param("userId") Long userId, @Param("minFocusSec") int minFocusSec, @Param("today") LocalDate today);

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

    // period 조회용 — 기간 안 statDate별 총공부/순공 합계 (focusSec >= minFocusSec 세션만, 기록 있는 날만, 오름차순).
    // 빈 날 채우기·직전 기간 합산은 서비스가 담당한다
    @Query("""
            select new project.study.studysession.dto.DailyStudyStat(s.statDate, sum(s.studySec), sum(s.focusSec))
            from StudySession s
            where s.userId = :userId and s.statDate between :from and :to and s.focusSec >= :minFocusSec
            group by s.statDate
            order by s.statDate""")
    List<DailyStudyStat> findDailyStudyStats(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec);

    // 세션 단건 상세 조회 — 소유자(userId)가 맞는 세션만. 없거나 남의 것이면 empty → 서비스가 404로 변환
    Optional<StudySession> findByIdAndUserId(Long id, Long userId);

    // 복구 판별용(BY-455) — 유저의 가장 최근 세션 1건(시작 시각 기준). 자정 분할이면 마지막 조각이 잡힌다
    Optional<StudySession> findFirstByUserIdOrderByStartedAtDesc(Long userId);

    // 복구 확인 처리(BY-455) — 한 제출(submissionStartedAt)의 미확인 자동 확정본 조각에만 확인 시각을 찍는다.
    // IS NULL 조건이라 동시 복구 요청 중 실제로 갱신한(반환>0) 쪽만 세션을 노출한다 — 한 번만 노출 보장.
    // auto_finalized 조건은 대체 레이스 방어다: 판정 read와 이 update 사이에 정상 제출이 그룹을 대체하면
    // 새 정상 rows(auto_finalized=false)는 여기서 제외돼 stale 요약이 반환되지 않는다(반환 0 → 404).
    @Modifying
    @Transactional
    @Query("""
            update StudySession s set s.recoveryAcknowledgedAt = :at
            where s.userId = :userId and s.submissionStartedAt = :submissionStartedAt
              and s.autoFinalized = true and s.recoveryAcknowledgedAt is null""")
    int acknowledgeRecovery(
            @Param("userId") Long userId,
            @Param("submissionStartedAt") Instant submissionStartedAt,
            @Param("at") Instant at);

    // 인터뷰 대상 판정(ADR-0027) — 자동 종료 세션도 시작한 세션으로 센다
    boolean existsByUserId(Long userId);

    // 인터뷰 대상 판정(ADR-0027) — 자동 종료 포함 가장 늦은 종료 시각, 세션이 없으면 null
    @Query("select max(s.endedAt) from StudySession s where s.userId = :userId")
    Instant findLastEndedAt(@Param("userId") Long userId);

    // 인터뷰 3번 그룹(ADR-0027) — since 이후 끝난 완료 세션 수. 자정 분할 조각은 루트 제출 시각으로 묶어 1건으로 세고
    // 순공은 합산한다. 완료 = 조각 모두 자동 종료가 아님 + 순공 합 minFocusSec 이상. 세션은 24시간을 넘지 않아
    // since 이후 끝난 묶음의 조각은 모두 since - 24시간 이후에 시작한다 — scanFrom으로 유니크 인덱스 범위만 읽는다
    @Query(value = """
            select count(*) from (
                select 1
                from study_session s
                where s.user_id = :userId and s.started_at > :scanFrom
                group by coalesce(s.submission_started_at, s.started_at)
                having bool_and(not s.auto_finalized)
                   and sum(s.focus_sec) >= :minFocusSec
                   and max(s.ended_at) > :since
            ) completed""", nativeQuery = true)
    long countCompletedSubmissionsEndedAfter(
            @Param("userId") Long userId,
            @Param("since") Instant since,
            @Param("scanFrom") Instant scanFrom,
            @Param("minFocusSec") int minFocusSec);

    // BY-828 백필 — 시간대 행이 없는 최근 세션. 순공 0·종료 시각 없는(레거시) 세션은 행이 생길 수 없어 뺀다
    @Query("""
            select s from StudySession s
            where s.statDate >= :from and s.focusSec > 0 and s.endedAt is not null and s.slots is empty""")
    List<StudySession> findSlotlessSince(@Param("from") LocalDate from);
}
