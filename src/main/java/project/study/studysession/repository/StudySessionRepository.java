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
import project.study.metrics.dto.HeavyUser;
import project.study.studysession.dto.DailyStudyStat;
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
            select new project.study.metrics.dto.HeavyUser(s.userId, count(distinct s.statDate))
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

    // 특정 날짜의 인정 기준(focusSec >= minFocusSec) 충족 세션 수 — 유저 무관 전체 집계
    long countByStatDateAndFocusSecGreaterThanEqual(LocalDate statDate, int minFocusSec);

    // 특정 날짜의 10분 이상 세션 + 소셜(룸 겹침) 판별. 세션 [started_at, ended_at]이 그 유저의
    // room_participations[joined_at, left_at]와 조금이라도 겹치면 social=true. left_at NULL은
    // 서버 재시작 등으로 닫히지 못한 "비정상 종료 구간"이라 겹침 판정에서 제외한다 (BY-415 설계문서
    // "알려진 한계" — NULL 구간은 분석에서 제외). 리포트는 어제 세션을 다음날 오전 10시에 집계하므로
    // 그 시점에 아직 열린 참여는 사실상 전부 stale이다 — 남겨두면 그 유저의 이후 모든 세션이 매일
    // 소셜로 오분류된다. room 도메인 엔티티를 참조하지 않도록 테이블명으로만 조인해 도메인 결합을 피한다
    @Query(value = """
                    select s.user_id as "userId", s.focus_sec as "focusSec",
                        exists (
                            select 1 from room_participations rp
                            where rp.user_id = s.user_id
                              and rp.left_at is not null
                              and rp.joined_at < s.ended_at
                              and s.started_at < rp.left_at
                        ) as "social"
                    from study_session s
                    where s.stat_date = :date and s.focus_sec >= :minFocusSec""", nativeQuery = true)
    List<QualifyingSessionRow> findQualifyingSessions(
            @Param("date") LocalDate date, @Param("minFocusSec") int minFocusSec);

    // 코호트 첫주 공부일수 — 유저별 생애 첫 10분 이상 공부일(d0)의 7일 창 [d0, d0+6]이 완결된
    // (d0 <= cohortCutoff) 유저만 코호트로 잡고, 각 유저가 그 창 안에서 10분 이상 공부한 날 수를
    // 유저당 한 행으로 반환한다(값은 1 이상). 코호트 평균·크기 계산은 서비스가 담당한다
    @Query(value = """
                    select count(distinct s.stat_date)
                    from (
                        select user_id, min(stat_date) as d0
                        from study_session
                        where focus_sec >= :minFocusSec
                        group by user_id
                        having min(stat_date) <= :cohortCutoff
                    ) f
                    join study_session s
                        on s.user_id = f.user_id
                       and s.focus_sec >= :minFocusSec
                       and s.stat_date between f.d0 and (f.d0 + 6)
                    group by f.user_id""", nativeQuery = true)
    List<Long> findCohortFirstWeekDays(
            @Param("minFocusSec") int minFocusSec, @Param("cohortCutoff") LocalDate cohortCutoff);

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
}
