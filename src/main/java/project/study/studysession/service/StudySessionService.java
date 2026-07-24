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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionResponse;
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
        List<StudySession> sessions = createSessions(request.userId(), request.startedAt(), request.endedAt(), events);
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
     */
    List<StudySession> createSessions(Long userId, Instant startedAt, Instant endedAt, List<StatusEvent> events) {
        // 분할 후 조각은 항상 24시간 이내가 되므로, 24시간 한도 등은 반드시 분할 전 원본 기준으로 먼저 검증한다
        validatePeriod(startedAt, endedAt);
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        validateEvents(startedAt, endedAt, sorted);

        List<StudySession> sessions = new ArrayList<>();
        Instant segmentStart = startedAt;
        Instant boundary = nextKstMidnight(startedAt);
        while (boundary.isBefore(endedAt)) {
            sessions.add(buildSession(userId, segmentStart, boundary, clip(sorted, segmentStart, boundary)));
            segmentStart = boundary;
            boundary = nextKstMidnight(boundary);
        }
        sessions.add(buildSession(userId, segmentStart, endedAt, clip(sorted, segmentStart, endedAt)));
        return sessions;
    }

    /** 검증이 끝난 한 구간을 세션 엔티티로 만든다 — 총시간·순공시간과 통계 귀속 날짜(KST 시작 날짜)를 계산한다. */
    private static StudySession buildSession(
            Long userId, Instant startedAt, Instant endedAt, List<StatusEvent> events) {
        long sessionSec = Duration.between(startedAt, endedAt).toSeconds();
        long nonFocusSec = events.stream()
                .mapToLong(event -> Duration.between(event.getStartedAt(), event.getEndedAt())
                        .toSeconds())
                .sum();
        LocalDate statDate = startedAt.atZone(KST).toLocalDate();
        return new StudySession(
                userId, statDate, startedAt, endedAt, (int) sessionSec, (int) (sessionSec - nonFocusSec), events);
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
}
