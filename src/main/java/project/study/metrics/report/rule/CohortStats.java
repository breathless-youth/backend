package project.study.metrics.report.rule;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;
import project.study.metrics.report.FirstWeekHabits;
import project.study.metrics.report.UserTimeline;

/** 중앙값 규칙(묶음 F 앞 두 개)이 비교할 코호트 중앙값. 값을 계산할 수 있는 유저만으로 구하고, 아무도 없으면 NaN. */
public record CohortStats(double medianPausesPerSession, double medianDistractionsPerFocusHour) {

    public static CohortStats of(List<UserTimeline> cohort) {
        return new CohortStats(
                median(cohort, FirstWeekHabits::pausesPerSession),
                median(cohort, FirstWeekHabits::distractionsPerFocusHour));
    }

    static double median(List<UserTimeline> cohort, Function<UserTimeline, OptionalDouble> metric) {
        double[] values = cohort.stream()
                .map(metric)
                .filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble)
                .sorted()
                .toArray();
        if (values.length == 0) {
            return Double.NaN;
        }
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }
}
