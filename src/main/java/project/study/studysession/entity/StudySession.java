package project.study.studysession.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "study_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "session_sec")
    private Integer sessionSec;

    @Column(name = "focus_sec")
    private Integer focusSec;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    @OrderBy("startedAt ASC")
    private List<StatusEvent> events = new ArrayList<>();

    private StudySession(
            Long userId,
            LocalDate statDate,
            Instant startedAt,
            Instant endedAt,
            int sessionSec,
            int focusSec,
            List<StatusEvent> events) {
        this.userId = userId;
        this.statDate = statDate;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.sessionSec = sessionSec;
        this.focusSec = focusSec;
        this.events = new ArrayList<>(events);
    }

    public static StudySession create(
            Long userId, Instant startedAt, Instant endedAt, List<StatusEvent> events, Clock clock) {
        validatePeriod(startedAt, endedAt, clock);
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        validateEvents(startedAt, endedAt, sorted);

        long sessionSec = Duration.between(startedAt, endedAt).toSeconds();
        long nonFocusSec = sorted.stream().mapToLong(StatusEvent::durationSec).sum();
        // 구간 시작 시각의 KST 날짜에 귀속한다 — 자정을 넘는 제출의 분할은 createAll이 담당
        LocalDate statDate = startedAt.atZone(KST).toLocalDate();

        return new StudySession(
                userId, statDate, startedAt, endedAt, (int) sessionSec, (int) (sessionSec - nonFocusSec), sorted);
    }

    /**
     * 세션을 KST 자정 경계로 분할해 생성한다. 자정을 넘지 않으면 세션 1개가 담긴 리스트를 반환하고,
     * 자정에 걸친 이벤트는 시각 기준으로 나뉘어 각 세션에 귀속된다.
     */
    public static List<StudySession> createAll(
            Long userId, Instant startedAt, Instant endedAt, List<StatusEvent> events, Clock clock) {
        // 분할 후 조각은 항상 24시간 이내가 되므로, 24시간 한도 등은 반드시 분할 전 원본 기준으로 먼저 검증한다
        validatePeriod(startedAt, endedAt, clock);
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        validateEvents(startedAt, endedAt, sorted);

        List<StudySession> sessions = new ArrayList<>();
        Instant segmentStart = startedAt;
        Instant boundary = nextKstMidnight(startedAt);
        while (boundary.isBefore(endedAt)) {
            sessions.add(create(userId, segmentStart, boundary, clip(sorted, segmentStart, boundary), clock));
            segmentStart = boundary;
            boundary = nextKstMidnight(boundary);
        }
        sessions.add(create(userId, segmentStart, endedAt, clip(sorted, segmentStart, endedAt), clock));
        return sessions;
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
    public static double focusRate(long focusSec, long sessionSec) {
        if (sessionSec <= 0) {
            return 0.0;
        }
        return Math.round(focusSec * 1000.0 / sessionSec) / 10.0;
    }

    public double focusRate() {
        return focusRate(focusSec, sessionSec);
    }

    private static void validatePeriod(Instant startedAt, Instant endedAt, Clock clock) {
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
