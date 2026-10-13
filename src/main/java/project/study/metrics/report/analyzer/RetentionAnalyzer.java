package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

/**
 * 📈 리텐션 (스펙 §4.2) — 7·30일은 "딱 그날"이 아니라 7일 구간이다. 구간이 다 끝난 최근 28일치 가입자만 센다.
 */
public final class RetentionAnalyzer {

    public enum Window {
        NEXT_DAY(1, 1),
        WEEK_TWO(7, 13),
        WEEK_FIVE(28, 34);

        private final int fromDay;
        private final int toDay;

        Window(int fromDay, int toDay) {
            this.fromDay = fromDay;
            this.toDay = toDay;
        }

        public int fromDay() {
            return fromDay;
        }

        public int toDay() {
            return toDay;
        }
    }

    private static final int COHORT_DAYS = 28;
    private static final int LAST_WEEK = 7;
    private static final int FIRST_WEEK_NEWEST = 6;
    private static final int FIRST_WEEK_OLDEST = FIRST_WEEK_NEWEST + COHORT_DAYS - 1;

    private RetentionAnalyzer() {}

    public static RetentionSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<RetentionRow> rows = Arrays.stream(Window.values())
                .map(window -> new RetentionRow(
                        window,
                        rate(data, anchor, window),
                        rate(data, anchor.minusDays(LAST_WEEK), window),
                        cohortFrom(anchor, window),
                        cohortTo(anchor, window)))
                .toList();
        LocalDate noneFrom = anchor.minusDays(FIRST_WEEK_OLDEST);
        LocalDate noneTo = anchor.minusDays(FIRST_WEEK_NEWEST);
        List<UserTimeline> cohort = data.signedUpBetween(noneFrom, noneTo);
        int none = (int) cohort.stream()
                .filter(user -> Cohorts.firstWeekStudyDays(user) == 0)
                .count();
        return new RetentionSection(rows, Rate.of(none, cohort.size()), noneFrom, noneTo);
    }

    static Rate rate(ReportData data, LocalDate anchor, Window window) {
        List<UserTimeline> cohort = data.signedUpBetween(cohortFrom(anchor, window), cohortTo(anchor, window));
        int retained = (int) cohort.stream()
                .filter(user -> user.studyDays(user.day(window.fromDay()), user.day(window.toDay())) >= 1)
                .count();
        return Rate.of(retained, cohort.size());
    }

    static LocalDate cohortTo(LocalDate anchor, Window window) {
        return anchor.minusDays(window.toDay());
    }

    static LocalDate cohortFrom(LocalDate anchor, Window window) {
        return anchor.minusDays(window.toDay() + COHORT_DAYS - 1L);
    }
}
