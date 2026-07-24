package project.study.studysession.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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
    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    @Transactional
    public List<StudySessionResponse> create(StudySessionCreateRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        List<StudySession> sessions =
                createSessions(request.userId(), request.startedAt(), request.endedAt(), request.focusSec(), events);
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            // FK 위반을 트랜잭션 커밋 전에 감지하기 위해 즉시 flush (한 트랜잭션이라 분할 저장도 원자적)
            studySessionRepository.flush();
            return saved.stream().map(this::toResponse).toList();
        } catch (DataIntegrityViolationException e) {
            throw new NotFoundException("존재하지 않는 사용자입니다: " + request.userId());
        }
    }

    /** 통계 날짜(statDate) 기준 하루 조회 — 기간(from~to) 조회는 추후 별도 메서드로 분리한다. */
    @Transactional(readOnly = true)
    public StudySessionListResponse list(Long userId, LocalDate date) {
        List<StudySession> sessions =
                studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(userId, date, date);
        long totalSessionSec =
                sessions.stream().mapToLong(StudySession::getSessionSec).sum();
        long totalFocusSec =
                sessions.stream().mapToLong(StudySession::getFocusSec).sum();

        // 프론트가 키 존재를 가정할 수 있도록 없는 상태도 0으로 채운다
        Map<EventStatus, Long> eventCounts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            eventCounts.put(status, 0L);
        }
        studySessionRepository
                .countEventsByStatus(userId, date, date)
                .forEach(count -> eventCounts.put(count.status(), count.count()));

        return new StudySessionListResponse(
                sessions.stream().map(this::toSummaryResponse).toList(),
                sessions.size(),
                totalSessionSec,
                totalFocusSec,
                focusRate(totalFocusSec, totalSessionSec),
                eventCounts);
    }

    private StudySessionResponse toResponse(StudySession session) {
        return StudySessionResponse.from(session, focusRate(session.getFocusSec(), session.getSessionSec()));
    }

    private StudySessionSummaryResponse toSummaryResponse(StudySession session) {
        return StudySessionSummaryResponse.from(session, focusRate(session.getFocusSec(), session.getSessionSec()));
    }

    /**
     * 세션을 KST 자정 경계로 분할해 생성한다. 자정을 넘지 않으면 세션 1개가 담긴 리스트를 반환하고,
     * 자정에 걸친 이벤트는 시각 기준으로 나뉘어 각 세션에 귀속된다.
     * 순공 시간(focusSec)은 앱이 제출한 값을 그대로 저장하되, 분할 시 조각 길이에 비례해 배분한다.
     */
    List<StudySession> createSessions(
            Long userId, Instant startedAt, Instant endedAt, int focusSec, List<StatusEvent> events) {
        // 분할 후 조각은 항상 24시간 이내가 되므로, 24시간 한도 등은 반드시 분할 전 원본 기준으로 먼저 검증한다
        validatePeriod(startedAt, endedAt);
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        // 이벤트 시간 검증
        validateEvents(startedAt, endedAt, sorted);

        // 조각 경계를 먼저 확정한다 (KST 자정 기준 — 24시간 한도 덕에 조각은 최대 2개)
        List<Instant> cuts = new ArrayList<>();
        cuts.add(startedAt);
        for (Instant boundary = nextKstMidnight(startedAt);
                boundary.isBefore(endedAt);
                boundary = nextKstMidnight(boundary)) {
            cuts.add(boundary);
        }
        cuts.add(endedAt);

        // 순공 시간의 검증·배분 기준은 원본 총 시간이 아니라 저장되는 조각별 총시간(내림 초)의 합이어야 한다.
        // sub-second 타임스탬프가 자정에 걸치면 원본 기준으로는 0으로 나누기(총 0초)나
        // focusSec > sessionSec인 조각(절삭 손실)이 생길 수 있다
        long[] segmentSecs = new long[cuts.size() - 1];
        long allocatableSec = 0;
        for (int i = 0; i < segmentSecs.length; i++) {
            segmentSecs[i] = Duration.between(cuts.get(i), cuts.get(i + 1)).toSeconds();
            allocatableSec += segmentSecs[i];
        }
        validateFocusSec(focusSec, allocatableSec);

        List<StudySession> sessions = new ArrayList<>();
        long allocatedFocusSec = 0;
        for (int i = 0; i < segmentSecs.length; i++) {
            long segmentFocusSec;
            if (i == segmentSecs.length - 1) {
                // 마지막 조각이 배분 나머지를 가져가 조각들의 순공 시간 합이 항상 요청값과 같다
                segmentFocusSec = focusSec - allocatedFocusSec;
            } else {
                segmentFocusSec = focusSec == 0 ? 0 : focusSec * segmentSecs[i] / allocatableSec;
            }
            sessions.add(buildSession(
                    userId,
                    cuts.get(i),
                    cuts.get(i + 1),
                    (int) segmentFocusSec,
                    clip(sorted, cuts.get(i), cuts.get(i + 1))));
            allocatedFocusSec += segmentFocusSec;
        }
        return sessions;
    }

    /** 검증이 끝난 한 구간을 세션 엔티티로 만든다 — 총시간과 통계 귀속 날짜(KST 시작 날짜)를 계산하고, 순공시간은 배분받은 값을 그대로 담는다. */
    private static StudySession buildSession(
            Long userId, Instant startedAt, Instant endedAt, int focusSec, List<StatusEvent> events) {
        long sessionSec = Duration.between(startedAt, endedAt).toSeconds();
        LocalDate statDate = startedAt.atZone(KST).toLocalDate();
        return new StudySession(userId, statDate, startedAt, endedAt, (int) sessionSec, focusSec, events);
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

    /** 집중률(%) — 순공시간 ÷ 총시간 × 100, 소수 1자리 반올림. */
    static double focusRate(long focusSec, long sessionSec) {
        if (sessionSec <= 0) {
            return 0.0;
        }
        return Math.round(focusSec * 1000.0 / sessionSec) / 10.0;
    }

    private void validatePeriod(Instant startedAt, Instant endedAt) {
        if (!endedAt.isAfter(startedAt)) {
            throw new InvalidSessionException("세션 종료 시각은 시작 시각 이후여야 합니다");
        }
        if (Duration.between(startedAt, endedAt).compareTo(MAX_DURATION) > 0) {
            throw new InvalidSessionException("세션은 24시간을 초과할 수 없습니다");
        }
        if (endedAt.isAfter(clock.instant().plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidSessionException("세션 종료 시각이 미래일 수 없습니다");
        }
    }

    private static void validateFocusSec(int focusSec, long totalSec) {
        if (focusSec < 0 || focusSec > totalSec) {
            throw new InvalidSessionException("순공 시간은 0 이상, 세션 총 시간 이하여야 합니다");
        }
    }

    private static void validateEvents(Instant startedAt, Instant endedAt, List<StatusEvent> sortedEvents) {
        StatusEvent previous = null;
        for (StatusEvent event : sortedEvents) {
            if (!event.getEndedAt().isAfter(event.getStartedAt())) {
                throw new InvalidSessionException("이벤트 종료 시각은 시작 시각 이후여야 합니다");
            }
            if (event.getStartedAt().isBefore(startedAt) || event.getEndedAt().isAfter(endedAt)) {
                throw new InvalidSessionException("이벤트는 세션 구간 안에 있어야 합니다");
            }
            if (previous != null && event.getStartedAt().isBefore(previous.getEndedAt())) {
                throw new InvalidSessionException("이벤트 구간이 서로 겹칠 수 없습니다");
            }
            previous = event;
        }
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
