package project.study.studysession.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import project.study.metrics.dto.HeavyUser;
import project.study.studysession.entity.StudySession;

/**
 * 지표 집계 전용 리포지토리 — 매일 오전 10시 Slack 리포트 등 전체 유저 대상 읽기 집계만 모은다.
 * 세션 생명주기(생성·복구·단건 조회)는 StudySessionRepository에 두어,
 * 쓰기 흐름 쿼리와 리포트 집계 쿼리가 한 인터페이스에 섞이지 않게 한다.
 */
public interface StudySessionMetricsRepository extends Repository<StudySession, Long> {

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
}
