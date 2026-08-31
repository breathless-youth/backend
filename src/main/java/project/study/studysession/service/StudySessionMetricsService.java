package project.study.studysession.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.dto.CohortFirstWeek;
import project.study.metrics.dto.HeavyUser;
import project.study.metrics.dto.QualifyingSession;
import project.study.studysession.StudySessionThresholds;
import project.study.studysession.repository.StudySessionRepository;

/**
 * 지표 집계 전용 서비스(매일 오전 10시 Slack 리포트 등) — 앱 화면용 {@code StudySessionService}와는
 * 분리해, 지표가 늘어나도 화면 서비스 파일이 커지지 않도록 한다.
 */
@Service
@RequiredArgsConstructor
public class StudySessionMetricsService {

    // 헤비유저 판정 구간 — anchorDate를 포함한 최근 7일([anchorDate - 6일, anchorDate])
    private static final int WINDOW_DAYS = 7;
    // 코호트 첫주 창 길이 — D0를 포함한 7일([D0, D0+6])
    private static final int COHORT_WINDOW_DAYS = 7;
    // 헤비유저로 인정하는 최소 스트릭 인정일수(구간 안에서)
    private static final long MIN_ACTIVE_DAYS = 3;

    private final StudySessionRepository studySessionRepository;

    /**
     * 헤비유저 조회 — {@code anchorDate}를 포함한 최근 {@value #WINDOW_DAYS}일 구간에서, 스트릭 인정
     * 기준(ADR-0009: 그 날짜의 세션 중 하나라도 focusSec이
     * {@value StudySessionThresholds#MIN_STREAK_FOCUS_SEC}초(10분) 이상)을 만족한 날이
     * {@value #MIN_ACTIVE_DAYS}일 이상인 유저를 인정일수 내림차순(동수면 userId 오름차순)으로 반환한다.
     * 앱 화면의 스트릭 판정과 같은 규칙을 써서, 사용자가 앱에서 보는 스트릭과 리포트 상의 헤비유저 판정이
     * 어긋나지 않도록 한다.
     *
     * <p>{@code anchorDate}에는 보통 "어제"를 넣는다 — 이 리포트는 매일 오전 10시에 발송되는데, 오늘은
     * 아직 하루가 끝나지 않아 세션이 부분적으로만 쌓인 상태라 집계 기준일로 삼기에 적절하지 않다.
     *
     * @param anchorDate 집계 구간의 마지막 날(포함) — 통상 "어제"
     * @return 헤비유저 목록(인정일수 내림차순, 동수면 userId 오름차순)
     */
    @Transactional(readOnly = true)
    public List<HeavyUser> findHeavyUsers(LocalDate anchorDate) {
        LocalDate from = anchorDate.minusDays(WINDOW_DAYS - 1L);
        return studySessionRepository.findHeavyUsers(
                from, anchorDate, StudySessionThresholds.MIN_STREAK_FOCUS_SEC, MIN_ACTIVE_DAYS);
    }

    /**
     * 해당 날짜에 스트릭 인정 기준을 충족한 세션 수(전체 유저 합계).
     * 헤비유저·스트릭과 같은 {@code MIN_STREAK_FOCUS_SEC}(ADR-0009) 잣대를 쓴다.
     */
    @Transactional(readOnly = true)
    public long countQualifyingSessionsOn(LocalDate date) {
        return studySessionRepository.countByStatDateAndFocusSecGreaterThanEqual(
                date, StudySessionThresholds.MIN_STREAK_FOCUS_SEC);
    }

    /**
     * 해당 날짜의 10분 이상 세션 목록(소셜/싱글 판별 포함). 소셜 vs 싱글 순공 비교와 세션 상세가
     * 같은 데이터를 쓰므로, 한 번 조회해 양쪽 지표를 파생시킨다.
     */
    @Transactional(readOnly = true)
    public List<QualifyingSession> findQualifyingSessions(LocalDate date) {
        return studySessionRepository.findQualifyingSessions(date, StudySessionThresholds.MIN_STREAK_FOCUS_SEC).stream()
                .map(row -> new QualifyingSession(row.getUserId(), row.getFocusSec(), row.getSocial()))
                .toList();
    }

    /**
     * 코호트 첫주 평균 공부일수 — 생애 첫 10분 이상 공부일(D0)의 7일 창 {@code [D0, D0+6]}이 완결된
     * (D0 ≤ {@code anchorDate} − 6) 유저들의, 창 안 10분 이상 공부일 수 평균.
     */
    @Transactional(readOnly = true)
    public CohortFirstWeek cohortFirstWeek(LocalDate anchorDate) {
        LocalDate cohortCutoff = anchorDate.minusDays(COHORT_WINDOW_DAYS - 1L);
        return CohortFirstWeek.from(studySessionRepository.findCohortFirstWeekDays(
                StudySessionThresholds.MIN_STREAK_FOCUS_SEC, cohortCutoff));
    }
}
