package project.study.studysession.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.dto.StudySessionSummaryResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@Service
@RequiredArgsConstructor
public class StudySessionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String STARTED_AT_UNIQUE_CONSTRAINT = "uq_study_session_user_started_at";
    // V1이 이름 없이 만든 FK의 PostgreSQL 자동 명명 규칙 이름
    private static final String USER_FK_CONSTRAINT = "study_session_user_id_fkey";

    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    @Transactional
    public List<StudySessionResponse> create(StudySessionCreateRequest request) {
        // 같은 (userId, startedAt) 루트 제출이 이미 있으면 재전송(강제종료 후 복구 등)으로 보고 저장 없이
        // 기존 결과(자정 분할 조각 포함)를 반환한다 — 재제출 본문의 다른 필드(endedAt 등)는 보지 않는다
        List<StudySession> existing = studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                request.userId(), request.startedAt());
        if (!existing.isEmpty()) {
            return existing.stream().map(this::toResponse).toList();
        }
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        List<StudySession> sessions = createSessions(
                request.userId(),
                request.startedAt(),
                request.endedAt(),
                request.studySec(),
                request.focusSec(),
                events);
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            // FK·유니크 위반을 트랜잭션 커밋 전에 감지하기 위해 즉시 flush (한 트랜잭션이라 분할 저장도 원자적)
            studySessionRepository.flush();
            return saved.stream().map(this::toResponse).toList();
        } catch (DataIntegrityViolationException e) {
            // 제약 이름으로 원인을 특정한다 — 유니크 위반은 동시 재전송 레이스이거나 기존 세션과
            // 시작 시각이 겹치는 제출(409), 유저 FK 위반은 404, 그 외 무결성 위반은 숨기지 않고 전파(500)
            String constraint = violatedConstraint(e);
            if (STARTED_AT_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new DuplicateSessionException("이미 같은 시각에 시작한 세션이 저장되어 있습니다");
            }
            if (USER_FK_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new NotFoundException("존재하지 않는 사용자입니다: " + request.userId());
            }
            throw e;
        }
    }

    /** 원인 체인에서 위반된 제약 이름을 찾는다 — 없으면 null. */
    private static String violatedConstraint(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    /**
     * 통계 날짜(statDate) 기준 하루 조회 — 기간(from~to) 조회는 추후 별도 메서드로 분리한다.
     * 캘린더 표시용으로 date가 속한 달의 공부한 날짜 목록(studiedDatesInMonth)도 함께 내려준다.
     * 상태별 이벤트 건수는 세션마다(sessions[].eventCounts) 내려주고, 그 합계도 함께(totalEventCounts) 내려준다.
     */
    @Transactional(readOnly = true)
    public StudySessionListResponse list(Long userId, LocalDate date) {
        List<StudySession> sessions =
                studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(userId, date, date);
        long totalStudySec =
                sessions.stream().mapToLong(StudySession::getStudySec).sum();
        long totalFocusSec =
                sessions.stream().mapToLong(StudySession::getFocusSec).sum();
        long longestFocusSec = sessions.stream()
                .mapToLong(StudySessionService::longestFocusStreakSec)
                .max()
                .orElse(0);
        List<StudySessionSummaryResponse> summaries =
                sessions.stream().map(this::toSummaryResponse).toList();
        Map<EventStatus, Long> totalEventCounts = countByStatus(
                sessions.stream().flatMap(s -> s.getEvents().stream()).toList());

        YearMonth month = YearMonth.from(date);
        List<LocalDate> studiedDatesInMonth =
                studySessionRepository.findDistinctStatDatesBetween(userId, month.atDay(1), month.atEndOfMonth());

        return new StudySessionListResponse(
                summaries,
                sessions.size(),
                totalStudySec,
                totalFocusSec,
                longestFocusSec,
                focusRate(totalFocusSec, totalStudySec),
                totalEventCounts,
                studiedDatesInMonth);
    }

    /** 세션 내부에서 이벤트(PHONE/DEVICE/AWAY/PAUSE)로 끊기지 않고 이어진 가장 긴 구간(초) — 이벤트가 없으면 세션 전체 길이. */
    private static long longestFocusStreakSec(StudySession session) {
        List<StatusEvent> sorted = session.getEvents().stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        Instant cursor = session.getStartedAt();
        long max = 0;
        for (StatusEvent event : sorted) {
            max = Math.max(max, Duration.between(cursor, event.getStartedAt()).toSeconds());
            cursor = event.getEndedAt();
        }
        return Math.max(max, Duration.between(cursor, session.getEndedAt()).toSeconds());
    }

    /** 상태별 이벤트 발생 건수 — 프론트가 키 존재를 가정할 수 있도록 없는 상태도 0으로 채운다. */
    private static Map<EventStatus, Long> countByStatus(List<StatusEvent> events) {
        Map<EventStatus, Long> counts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            counts.put(status, 0L);
        }
        events.forEach(event -> counts.merge(event.getStatus(), 1L, Long::sum));
        return counts;
    }

    private StudySessionResponse toResponse(StudySession session) {
        return StudySessionResponse.from(session, focusRate(session.getFocusSec(), session.getStudySec()));
    }

    private StudySessionSummaryResponse toSummaryResponse(StudySession session) {
        return StudySessionSummaryResponse.from(
                session, focusRate(session.getFocusSec(), session.getStudySec()), countByStatus(session.getEvents()));
    }

    /**
     * 세션을 KST 자정 경계로 분할해 생성한다. 자정을 넘지 않으면 세션 1개가 담긴 리스트를 반환하고,
     * 자정에 걸친 이벤트는 시각 기준으로 나뉘어 각 세션에 귀속된다.
     * 총 공부 시간(studySec)과 순공 시간(focusSec)은 앱이 제출한 값을 그대로 저장하되, 분할 시
     * 조각 길이에 비례해 배분한다. PAUSE(일시정지)는 총공부시간 타이머도 멈추므로 studySec 배분
     * 가중치에서 제외되고, 나머지 이벤트(PHONE/DEVICE/AWAY)는 순공시간 타이머만 멈추므로
     * focusSec 배분 가중치에서만 제외된다.
     */
    List<StudySession> createSessions(
            Long userId, Instant startedAt, Instant endedAt, int studySec, int focusSec, List<StatusEvent> events) {
        // 분할 후 조각은 항상 24시간 이내가 되므로, 24시간 한도 등은 반드시 분할 전 원본 기준으로 먼저 검증한다
        StudySessionValidator.validatePeriod(startedAt, endedAt, clock.instant());
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        StudySessionValidator.validateEvents(startedAt, endedAt, sorted);

        List<Instant> cuts = computeCuts(startedAt, endedAt);
        SegmentWeights weights = computeSegmentWeights(cuts, sorted);

        StudySessionValidator.validateStudySec(studySec, weights.totalStudyActiveSec());
        StudySessionValidator.validateFocusSec(focusSec, studySec);

        return buildSessions(userId, cuts, weights, studySec, focusSec);
    }

    /** 조각 경계를 확정한다 (KST 자정 기준 — 24시간 한도 덕에 조각은 최대 2개). */
    private static List<Instant> computeCuts(Instant startedAt, Instant endedAt) {
        List<Instant> cuts = new ArrayList<>();
        cuts.add(startedAt);
        for (Instant boundary = nextKstMidnight(startedAt);
                boundary.isBefore(endedAt);
                boundary = nextKstMidnight(boundary)) {
            cuts.add(boundary);
        }
        cuts.add(endedAt);
        return cuts;
    }

    /** 조각별 이벤트 클립과, studySec/focusSec 배분 가중치(studyActiveSec/focusActiveSec) 및 그 합계. */
    private record SegmentWeights(
            List<List<StatusEvent>> segmentEvents,
            long[] studyActiveSecs,
            long[] focusActiveSecs,
            long totalStudyActiveSec,
            long totalFocusActiveSec) {}

    /**
     * 검증·배분 기준은 원본 총 시간이 아니라 저장되는 조각별 총시간(내림 초)의 합이어야 한다.
     * sub-second 타임스탬프가 자정에 걸치면 원본 기준으로는 0으로 나누기(총 0초)나 절삭 손실이
     * 생길 수 있다. 조각마다 PAUSE만 뺀 studyActiveSec과 전체 이벤트를 뺀 focusActiveSec을
     * 함께 구해 각각의 배분 가중치로 쓴다.
     */
    private static SegmentWeights computeSegmentWeights(List<Instant> cuts, List<StatusEvent> sorted) {
        int segmentCount = cuts.size() - 1;
        List<List<StatusEvent>> segmentEvents = new ArrayList<>(segmentCount);
        long[] studyActiveSecs = new long[segmentCount];
        long[] focusActiveSecs = new long[segmentCount];
        long totalStudyActiveSec = 0;
        long totalFocusActiveSec = 0;
        for (int i = 0; i < segmentCount; i++) {
            long segmentSec = Duration.between(cuts.get(i), cuts.get(i + 1)).toSeconds();
            List<StatusEvent> clipped = clip(sorted, cuts.get(i), cuts.get(i + 1));
            segmentEvents.add(clipped);
            long stopSec = sumDuration(clipped, EventStatus.PAUSE);
            long eventSec = sumDuration(clipped);
            studyActiveSecs[i] = segmentSec - stopSec;
            focusActiveSecs[i] = segmentSec - eventSec;
            totalStudyActiveSec += studyActiveSecs[i];
            totalFocusActiveSec += focusActiveSecs[i];
        }
        return new SegmentWeights(
                segmentEvents, studyActiveSecs, focusActiveSecs, totalStudyActiveSec, totalFocusActiveSec);
    }

    /** 조각별 가중치대로 studySec/focusSec을 비례 배분해 세션들을 만든다 — 마지막 조각이 배분 나머지를 가져가 합이 항상 요청값과 같다. */
    private static List<StudySession> buildSessions(
            Long userId, List<Instant> cuts, SegmentWeights weights, int studySec, int focusSec) {
        int segmentCount = cuts.size() - 1;
        List<StudySession> sessions = new ArrayList<>();
        long allocatedStudySec = 0;
        long allocatedFocusSec = 0;
        for (int i = 0; i < segmentCount; i++) {
            long segmentStudySec;
            long segmentFocusSec;
            if (i == segmentCount - 1) {
                segmentStudySec = studySec - allocatedStudySec;
                segmentFocusSec = focusSec - allocatedFocusSec;
            } else {
                segmentStudySec =
                        studySec == 0 ? 0 : studySec * weights.studyActiveSecs()[i] / weights.totalStudyActiveSec();
                // focusActiveSec 합이 0(전 구간이 이벤트로 덮인 경우)이면 studyActiveSec 비율로 대체 배분
                boolean noFocusActiveTime = weights.totalFocusActiveSec() == 0;
                long focusWeight = noFocusActiveTime ? weights.studyActiveSecs()[i] : weights.focusActiveSecs()[i];
                long focusWeightTotal =
                        noFocusActiveTime ? weights.totalStudyActiveSec() : weights.totalFocusActiveSec();
                segmentFocusSec = focusSec == 0 ? 0 : focusSec * focusWeight / focusWeightTotal;
            }
            sessions.add(buildSession(
                    userId,
                    cuts.get(i),
                    cuts.get(i + 1),
                    (int) segmentStudySec,
                    (int) segmentFocusSec,
                    weights.segmentEvents().get(i)));
            allocatedStudySec += segmentStudySec;
            allocatedFocusSec += segmentFocusSec;
        }
        // 조각들이 원본 제출의 시작 시각을 루트로 공유해야 재제출 판별·응답 조회가 조각 단위로 어긋나지 않는다
        sessions.forEach(session -> session.attachToSubmission(cuts.get(0)));
        return sessions;
    }

    /** 검증이 끝난 한 구간을 세션 엔티티로 만든다 — 통계 귀속 날짜(KST 시작 날짜)만 계산하고, 총공부·순공 시간은 배분받은 값을 그대로 담는다. */
    private static StudySession buildSession(
            Long userId, Instant startedAt, Instant endedAt, int studySec, int focusSec, List<StatusEvent> events) {
        LocalDate statDate = startedAt.atZone(KST).toLocalDate();
        return new StudySession(userId, statDate, startedAt, endedAt, studySec, focusSec, events);
    }

    private static long sumDuration(List<StatusEvent> events) {
        return events.stream()
                .mapToLong(
                        e -> Duration.between(e.getStartedAt(), e.getEndedAt()).toSeconds())
                .sum();
    }

    private static long sumDuration(List<StatusEvent> events, EventStatus status) {
        return events.stream()
                .filter(e -> e.getStatus() == status)
                .mapToLong(
                        e -> Duration.between(e.getStartedAt(), e.getEndedAt()).toSeconds())
                .sum();
    }

    private static Instant nextKstMidnight(Instant instant) {
        return instant.atZone(KST).toLocalDate().plusDays(1).atStartOfDay(KST).toInstant();
    }

    /** 이벤트들을 [segmentStart, segmentEnd) 구간으로 잘라낸다. 0초 조각은 버린다. */
    private static List<StatusEvent> clip(List<StatusEvent> sortedEvents, Instant segmentStart, Instant segmentEnd) {
        List<StatusEvent> clipped = new ArrayList<>();
        for (StatusEvent event : sortedEvents) {
            Instant start = event.getStartedAt().isAfter(segmentStart) ? event.getStartedAt() : segmentStart;
            Instant end = event.getEndedAt().isBefore(segmentEnd) ? event.getEndedAt() : segmentEnd;
            if (start.isBefore(end)) {
                // 조각 세션들이 같은 이벤트 엔티티를 공유하면 cascade 저장 시 한쪽이 행을 가져가므로 항상 새로 만든다
                clipped.add(new StatusEvent(event.getStatus(), start, end));
            }
        }
        return clipped;
    }

    /** 집중률(%) — 순공시간 ÷ 총공부시간 × 100, 소수 1자리 반올림. */
    static double focusRate(long focusSec, long studySec) {
        if (studySec <= 0) {
            return 0.0;
        }
        return Math.round(focusSec * 1000.0 / studySec) / 10.0;
    }

    /**
     * 연속 공부일(스트릭) 조회 — 유저에 상태로 저장하지 않고 세션 이력(statDate)에서 매번 계산한다.
     * 저장 방식은 갱신 시점(제출·조회 순서)에 따라 어긋날 수 있지만, 이력 계산은 항상 정합하다.
     * 기록이 없거나 존재하지 않는 userId면 0/0 — 목록 조회와 같은 계약이다.
     */
    @Transactional(readOnly = true)
    public StudySessionStreakResponse streak(Long userId) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        // 시계 오차 허용(5분) 탓에 자정 직후 조각이 내일 날짜로 저장될 수 있다 — 공부일은 오늘까지만 센다
        List<LocalDate> statDates = studySessionRepository.findDistinctStatDates(userId).stream()
                .filter(date -> !date.isAfter(today))
                .toList();
        return new StudySessionStreakResponse(currentStreak(statDates, today), maxStreak(statDates));
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
}
