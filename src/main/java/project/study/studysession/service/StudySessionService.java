package project.study.studysession.service;

import static project.study.studysession.service.StudySessionSplitter.*;
import static project.study.studysession.service.StudySessionSplitter.computeCuts;
import static project.study.studysession.service.StudySessionValidator.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.NotFoundException;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.dto.StudyDaysResponse;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.dto.StudySessionSummaryResponse;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@Service
@RequiredArgsConstructor
public class StudySessionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String STARTED_AT_UNIQUE_CONSTRAINT = "uq_study_session_user_started_at";
    // V1이 이름 없이 만든 FK의 PostgreSQL 자동 명명 규칙 이름
    private static final String USER_FK_CONSTRAINT = "study_session_user_id_fkey";

    /** 1분 — 조회에 보이는 최소 순공시간. */
    public static final int MIN_LIST_FOCUS_SEC = 60;
    /** 10분 — 스트릭 인정 최소 순공시간(세션 단위). */
    public static final int MIN_STREAK_FOCUS_SEC = 600;

    private final StudySessionRepository studySessionRepository;
    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final Clock clock;
    private final SubjectLookupProvider subjectLookupProvider;

    /** autoFinalized=true는 확정 스케줄러 전용 — 저장되는 세션에 자동 확정 표시를 남긴다. 완료 할 일은 없다 (ADR-0022). */
    @Transactional
    public List<StudySessionResponse> create(Long userId, StudySessionCreateRequest request, boolean autoFinalized) {
        return create(userId, request, List.of(), autoFinalized);
    }

    /**
     * completedTasks는 컨트롤러가 StudySubjectService.assertTasksOwned로 검증해 넘긴 값이다 — 서비스는
     * request.completedTaskIds()를 직접 읽지 않는다(과목 소유 검증과 같은 배치, ADR-0021 §6).
     */
    @Transactional
    public List<StudySessionResponse> create(
            Long userId, StudySessionCreateRequest request, List<CompletedTask> completedTasks, boolean autoFinalized) {
        List<StudySession> existing = studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                userId, request.startedAt());
        if (!existing.isEmpty()) {
            // 클라 제출본이 하나라도 있으면 불가침 — 기존 멱등 동작(저장된 결과 반환)
            if (!existing.stream().allMatch(StudySession::isAutoFinalized)) {
                return toResponses(existing);
            }
            // 전부 자동 확정본이면 잠정 기록 — 새 도착분(최종 제출·재확정)으로 대체한다. 길이 비교는 하지 않는다: 스냅샷이 누적값이라 나중 도착분이 항상 상위집합이다 (ADR-0014)
            studySessionRepository.deleteAll(existing);
            studySessionRepository.flush();
        }
        List<StatusEvent> events = request.getStatusEventList();

        List<StudySession> sessions = validateAndBuildSessions(
                userId,
                request.startedAt(),
                request.endedAt(),
                request.studySec(),
                request.focusSec(),
                events,
                new SessionAttachments(request.subjectSegmentsOrEmpty(), completedTasks));
        if (autoFinalized) {
            sessions.forEach(StudySession::markAutoFinalized);
        }
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            studySessionRepository.flush();
            activeStudySessionRepository.deleteByUserIdAndStartedAt(userId, request.startedAt());
            return toResponses(saved);
        } catch (DataIntegrityViolationException e) {
            String constraint = violatedConstraint(e);
            if (STARTED_AT_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new DuplicateSessionException("이미 같은 시각에 시작한 세션이 저장되어 있습니다");
            }
            if (USER_FK_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new NotFoundException("존재하지 않는 사용자입니다: " + userId);
            }
            throw e;
        }
    }

    /**
     * 유니크 위반으로 create()가 던진 DuplicateSessionException을 받은 호출자가 재조회할 때 쓴다.
     * create()의 트랜잭션은 flush 실패 시점에 이미 롤백되어 끝났으므로, 같은 (userId, submissionStartedAt)의 레이스에서 진 것뿐이라면 이 완전히 새 트랜잭션에서 상대가 커밋한 결과를 찾아 그대로 반환한다(멱등).
     */
    @Transactional(readOnly = true)
    public List<StudySessionResponse> findExistingSubmission(Long userId, Instant submissionStartedAt) {
        return toResponses(studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                userId, submissionStartedAt));
    }

    /** 원인 체인에서 위반된 제약 이름을 찾는다 — 없으면 null. */
    static String violatedConstraint(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    /**
     * 통계 날짜(statDate) 기준 하루 조회 — 기간(from~to) 조회는 추후 별도 메서드로 분리한다.
     * 순공시간(focusSec)이 {@value project.study.studysession.StudySessionThresholds#MIN_LIST_FOCUS_SEC}초(1분)
     * 미만인 세션은 저장은 되어도 조회엔 보이지 않는다.
     * 캘린더 표시용으로 date가 속한 달의 공부한 날짜 목록(studiedDatesInMonth)도 함께 내려준다.
     * 상태별 이벤트 건수는 세션마다(sessions[].eventCounts) 내려주고, 그 합계도 함께(totalEventCounts) 내려준다.
     */
    @Transactional(readOnly = true)
    public StudySessionListResponse list(Long userId, LocalDate date) {
        return list(userId, date, true);
    }

    /**
     * includeNames=false는 구 앱(API-Version 1) 경로용이다 — 그 경로는 토큰 없이 쿼리 userId로 열려 있어 과목·할 일 이름까지
     * 실으면 익명 노출이 늘어난다(Codex 리뷰 P1). 구 앱은 그 필드를 읽지 않으므로 빈 배열로 둔다. 구간(id)·이벤트는 그대로다.
     */
    @Transactional(readOnly = true)
    public StudySessionListResponse list(Long userId, LocalDate date, boolean includeNames) {
        List<StudySession> sessions =
                studySessionRepository.findInPeriodWithMinFocusSec(userId, date, date, MIN_LIST_FOCUS_SEC);
        long totalStudySec =
                sessions.stream().mapToLong(StudySession::getStudySec).sum();
        long totalFocusSec =
                sessions.stream().mapToLong(StudySession::getFocusSec).sum();
        long longestFocusSec = sessions.stream()
                .mapToLong(StudySessionStatsCalculator::longestFocusStreakSec)
                .max()
                .orElse(0);

        SubjectLookup lookup = includeNames ? lookupFor(sessions) : SubjectLookup.EMPTY;
        List<StudySessionSummaryResponse> summaries = sessions.stream()
                .map(session -> toSummaryResponse(session, lookup))
                .toList();
        Map<EventStatus, Long> totalEventCounts = StudySessionStatsCalculator.countByStatus(
                sessions.stream().flatMap(s -> s.getEvents().stream()).toList());

        YearMonth month = YearMonth.from(date);
        List<LocalDate> studiedDatesInMonth = studySessionRepository.findDistinctStatDatesBetween(
                userId, month.atDay(1), month.atEndOfMonth(), MIN_LIST_FOCUS_SEC);

        return new StudySessionListResponse(
                summaries,
                sessions.size(),
                totalStudySec,
                totalFocusSec,
                longestFocusSec,
                StudySessionStatsCalculator.focusRate(totalFocusSec, totalStudySec),
                totalEventCounts,
                studiedDatesInMonth,
                lookup.allSubjects());
    }

    /** 세션들이 참조한 과목·할 일 이름을 한 번에 조회한다 (BY-734) — 둘 다 없으면 조회하지 않는다. */
    private SubjectLookup lookupFor(List<StudySession> sessions) {
        Set<Long> subjectIds = sessions.stream()
                .flatMap(session -> session.getSubjectSegments().stream())
                .map(StudySessionSubjectSegment::getSubjectId)
                .collect(Collectors.toSet());
        Set<Long> taskIds = sessions.stream()
                .flatMap(session -> session.getCompletedTaskIds().stream())
                .collect(Collectors.toSet());
        if (subjectIds.isEmpty() && taskIds.isEmpty()) {
            return SubjectLookup.EMPTY;
        }
        return subjectLookupProvider.lookup(subjectIds, taskIds);
    }

    private List<StudySessionResponse> toResponses(List<StudySession> sessions) {
        SubjectLookup lookup = lookupFor(sessions);
        return sessions.stream().map(session -> toResponse(session, lookup)).toList();
    }

    private StudySessionResponse toResponse(StudySession session, SubjectLookup lookup) {
        return StudySessionResponse.from(
                session, StudySessionStatsCalculator.focusRate(session.getFocusSec(), session.getStudySec()), lookup);
    }

    private StudySessionSummaryResponse toSummaryResponse(StudySession session, SubjectLookup lookup) {
        return StudySessionSummaryResponse.from(
                session,
                StudySessionStatsCalculator.focusRate(session.getFocusSec(), session.getStudySec()),
                StudySessionStatsCalculator.countByStatus(session.getEvents()),
                lookup);
    }

    /**
     * 세션을 KST 자정 경계로 분할해 생성한다 — 안 넘으면 1개, 걸친 이벤트는 시각 기준으로 각 세션에 귀속.
     * studySec/focusSec은 제출값을 조각 길이 비례로 배분한다. PAUSE는 총공부·순공 타이머를 모두 멈추므로
     * 두 배분 가중치에서 다 빠지고, 나머지 이벤트(PHONE/DEVICE/AWAY/SLEEP)는 순공 타이머만 멈추므로 focusSec 배분에서만 빠진다.
     */
    List<StudySession> validateAndBuildSessions(
            Long userId, Instant startedAt, Instant endedAt, int studySec, int focusSec, List<StatusEvent> events) {
        return validateAndBuildSessions(
                userId, startedAt, endedAt, studySec, focusSec, events, SessionAttachments.NONE);
    }

    /** 과목 구간(subjectSegments)도 함께 검증하고 조각마다 잘라 그 조각의 이벤트로 과목별 시간을 계산한다 (ADR-0023). */
    List<StudySession> validateAndBuildSessions(
            Long userId,
            Instant startedAt,
            Instant endedAt,
            int studySec,
            int focusSec,
            List<StatusEvent> events,
            List<SubjectSegmentRequest> subjectSegments) {
        return validateAndBuildSessions(
                userId,
                startedAt,
                endedAt,
                studySec,
                focusSec,
                events,
                new SessionAttachments(subjectSegments, List.of()));
    }

    /** 완료 할 일(attachments.completedTasks)은 배분하지 않고 완료 시각이 속한 조각에 붙인다 (ADR-0022). */
    List<StudySession> validateAndBuildSessions(
            Long userId,
            Instant startedAt,
            Instant endedAt,
            int studySec,
            int focusSec,
            List<StatusEvent> events,
            SessionAttachments attachments) {
        // 분할 후 조각은 항상 24시간 이내가 되므로, 24시간 한도 등은 반드시 분할 전 원본 기준으로 먼저 검증한다
        validatePeriod(startedAt, endedAt, clock.instant());

        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        validateEvents(startedAt, endedAt, sorted);

        List<Instant> cuts = computeCuts(startedAt, endedAt);
        SegmentWeights weights = computeSegmentWeights(cuts, sorted);

        // 조각 이후에 검증
        validateStudySec(studySec, weights.totalStudyActiveSec());
        validateFocusSec(focusSec, studySec);
        List<SubjectSegmentRequest> sortedSegments = attachments.subjectSegments().stream()
                .sorted(Comparator.comparing(SubjectSegmentRequest::startedAt))
                .toList();
        validateSubjectSegments(startedAt, endedAt, sortedSegments);

        return buildSessions(userId, cuts, weights, studySec, focusSec, sortedSegments, attachments.completedTasks());
    }

    /**
     * 연속 공부일(스트릭) 조회 — 유저에 상태로 저장하지 않고 세션 이력(statDate)에서 매번 계산한다.
     * 그 날 세션 중 하나라도 순공시간이
     * {@value project.study.studysession.StudySessionThresholds#MIN_STREAK_FOCUS_SEC}초(10분) 이상이면 그 날은 스트릭에 잡힌다
     * (하루 합계가 아니라 세션 단위 기준). 기록이 없거나 존재하지 않는 userId면 0/0 — 목록 조회와 같은 계약이다.
     * from/to를 함께 주면 그 기간 중 스트릭 인정 기준을 만족한 날짜 목록(studiedDatesInRange)도 계산한다 — 선택 파라미터.
     */
    @Transactional(readOnly = true)
    public StudySessionStreakResponse streak(Long userId, LocalDate from, LocalDate to) {
        StudySessionValidator.validateDateRange(from, to);
        LocalDate today = clock.instant().atZone(KST).toLocalDate();

        // 시계 오차 허용(5분) 탓에 자정 직후 조각이 내일 날짜로 저장될 수 있다 — 공부일은 오늘까지만 센다
        List<LocalDate> statDates = studySessionRepository.findDistinctStatDates(userId, MIN_STREAK_FOCUS_SEC).stream()
                .filter(date -> !date.isAfter(today))
                .toList();

        List<LocalDate> studiedDatesInRange = from == null
                ? List.of()
                : studySessionRepository.findDistinctStatDatesBetween(userId, from, to, MIN_STREAK_FOCUS_SEC);
        return new StudySessionStreakResponse(
                currentStreak(statDates, today), maxStreak(statDates), studiedDatesInRange);
    }

    /**
     * 누적 공부일 — 가입 이후 순공시간 1분 이상 세션이 하루라도 있었던 날의 수. 목록 조회와 같은 기준(ADR-0009)이라
     * "목록에 세션이 보이는 날"과 일치한다. 스트릭과 같은 이유로 오늘(KST)까지만 센다.
     */
    @Transactional(readOnly = true)
    public StudyDaysResponse studyDays(Long userId) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        return new StudyDaysResponse(studySessionRepository.countDistinctStatDates(userId, MIN_LIST_FOCUS_SEC, today));
    }

    /** 오늘(기록이 아직 없으면 어제)부터 거꾸로 이어진 연속 공부일. 오늘이 지나기 전엔 스트릭이 끊긴 게 아니다. */
    private static int currentStreak(List<LocalDate> statDates, LocalDate today) {
        Set<LocalDate> dates = Set.copyOf(statDates);
        LocalDate day = dates.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (dates.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /** 전체 이력에서 가장 길었던 연속 공부일 — statDates는 내림차순 정렬·중복 없음을 전제한다. */
    private static int maxStreak(List<LocalDate> statDates) {
        int max = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate date : statDates) {
            run = previous != null && previous.minusDays(1).equals(date) ? run + 1 : 1;
            max = Math.max(max, run);
            previous = date;
        }
        return max;
    }

    @Transactional(readOnly = true)
    public StudyPeriodStatsResponse periodStats(
            Long userId, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {
        return StudySessionStatsCalculator.periodStats(
                studySessionRepository, userId, from, to, compareFrom, compareTo);
    }

    @Transactional(readOnly = true)
    public StudySessionResponse findById(Long userId, Long id) {
        return findById(userId, id, true);
    }

    /** includeNames=false는 구 앱 경로용 — list와 같은 이유로 과목·할 일 이름을 싣지 않는다. */
    @Transactional(readOnly = true)
    public StudySessionResponse findById(Long userId, Long id, boolean includeNames) {
        StudySession session = studySessionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다"));
        return toResponse(session, includeNames ? lookupFor(List.of(session)) : SubjectLookup.EMPTY);
    }
}
