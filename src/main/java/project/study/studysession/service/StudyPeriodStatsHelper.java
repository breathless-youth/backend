package project.study.studysession.service;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.repository.StudySessionRepository;

/** Utility for period stats computation. */
public class StudyPeriodStatsHelper {
    private static final long MAX_PERIOD_DAYS = 366;

    static StudyPeriodStatsResponse compute(
            StudySessionRepository repo, Long userId, LocalDate from, LocalDate to, LocalDate cFrom, LocalDate cTo) {
        validatePeriod(from, to);
        validateRange(cFrom, cTo);
        Map<LocalDate, DailyStudyStat> byDate = repo.findDailyStudyStats(userId, from, to, MIN_LIST_FOCUS_SEC).stream()
                .collect(Collectors.toMap(DailyStudyStat::date, Function.identity()));
        List<DailyStudyStat> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            daily.add(byDate.getOrDefault(d, new DailyStudyStat(d, 0L, 0L)));
        }
        long totalStudySec = daily.stream().mapToLong(DailyStudyStat::studySec).sum();
        long totalFocusSec = daily.stream().mapToLong(DailyStudyStat::focusSec).sum();
        Long prevFocus = null;
        if (cFrom != null) {
            prevFocus = repo.findDailyStudyStats(userId, cFrom, cTo, MIN_LIST_FOCUS_SEC).stream()
                    .mapToLong(DailyStudyStat::focusSec)
                    .sum();
        }
        return new StudyPeriodStatsResponse(from, to, totalStudySec, totalFocusSec, prevFocus, daily);
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidSessionException("from은 to보다 이후일 수 없습니다");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new InvalidSessionException("조회 범위가 너무 넓습니다 (최대 " + MAX_PERIOD_DAYS + "일)");
        }
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new InvalidSessionException("from과 to는 함께 지정해야 합니다");
        }
        if (from != null && from.isAfter(to)) {
            throw new InvalidSessionException("from은 to보다 이후일 수 없습니다");
        }
    }

    private StudyPeriodStatsHelper() {}
}
