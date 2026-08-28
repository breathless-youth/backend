package project.study.studysession.service;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

/**
 * StudySessionService가 쓰는 순수 통계 계산 로직 모음 — 상태 없이 값만 계산한다.
 * StudySessionService.java가 checkstyle FileLength(400줄) 제약에 걸려 이쪽으로 뺐다.
 */
final class StudySessionStatsCalculator {

    private static final long MAX_PERIOD_DAYS = 366;

    private StudySessionStatsCalculator() {}

    /** 집중률(%) — 순공시간 ÷ 총공부시간 × 100, 소수 1자리 반올림. */
    static double focusRate(long focusSec, long studySec) {
        if (studySec <= 0) {
            return 0.0;
        }
        return Math.round(focusSec * 1000.0 / studySec) / 10.0;
    }

    /** 상태별 이벤트 발생 건수 — 프론트가 키 존재를 가정할 수 있도록 없는 상태도 0으로 채운다. */
    static Map<EventStatus, Long> countByStatus(List<StatusEvent> events) {
        Map<EventStatus, Long> counts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            counts.put(status, 0L);
        }
        events.forEach(event -> counts.merge(event.getStatus(), 1L, Long::sum));
        return counts;
    }

    /** 세션 내부에서 이벤트(PHONE/DEVICE/AWAY/PAUSE)로 끊기지 않고 이어진 가장 긴 구간(초) — 이벤트가 없으면 세션 전체 길이. */
    static long longestFocusStreakSec(StudySession session) {
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

    /** from~to 기간 집계 — 기록 없는 날은 0으로 채우고, compare 지정 시 직전 구간 순공 합계도 계산한다. */
    static StudyPeriodStatsResponse periodStats(
            StudySessionRepository repository,
            Long userId,
            LocalDate from,
            LocalDate to,
            LocalDate compareFrom,
            LocalDate compareTo) {
        validatePeriod(from, to);
        StudySessionService.validateRange(compareFrom, compareTo);

        Map<LocalDate, DailyStudyStat> byDate =
                repository.findDailyStudyStats(userId, from, to, MIN_LIST_FOCUS_SEC).stream()
                        .collect(Collectors.toMap(DailyStudyStat::date, Function.identity()));

        List<DailyStudyStat> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            daily.add(byDate.getOrDefault(d, new DailyStudyStat(d, 0L, 0L)));
        }
        long totalStudySec = daily.stream().mapToLong(DailyStudyStat::studySec).sum();
        long totalFocusSec = daily.stream().mapToLong(DailyStudyStat::focusSec).sum();

        Long previousTotalFocusSec = null;
        if (compareFrom != null) {
            previousTotalFocusSec =
                    repository.findDailyStudyStats(userId, compareFrom, compareTo, MIN_LIST_FOCUS_SEC).stream()
                            .mapToLong(DailyStudyStat::focusSec)
                            .sum();
        }
        return new StudyPeriodStatsResponse(from, to, totalStudySec, totalFocusSec, previousTotalFocusSec, daily);
    }

    /** from/to는 필수이고 from<=to, 범위는 최대 MAX_PERIOD_DAYS일. */
    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidSessionException("from은 to보다 이후일 수 없습니다");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new InvalidSessionException("조회 범위가 너무 넓습니다 (최대 " + MAX_PERIOD_DAYS + "일)");
        }
    }
}
