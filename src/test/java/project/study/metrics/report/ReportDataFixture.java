package project.study.metrics.report;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import project.study.metrics.report.fact.ExperimentData;
import project.study.metrics.report.fact.ExperimentFact;

/** 테스트용 — 타임라인 목록을 ReportData로. amplitude=true면 [A−61, A] 전부 수집된 것으로 본다. */
public final class ReportDataFixture {

    private ReportDataFixture() {}

    public static ReportData data(LocalDate anchor, boolean amplitude, UserTimeline... users) {
        return data(anchor, amplitude, List.of(), users);
    }

    public static ReportData data(
            LocalDate anchor, boolean amplitude, List<ExperimentFact> experiment, UserTimeline... users) {
        Set<LocalDate> dates =
                amplitude ? anchor.minusDays(61).datesUntil(anchor.plusDays(1)).collect(Collectors.toSet()) : Set.of();
        Set<Long> appOpened = new HashSet<>();
        for (UserTimeline user : users) {
            if (AmplitudeKeys.APP_OPEN.stream().anyMatch(key -> user.amplitudeCount(key, anchor, anchor) > 0)) {
                appOpened.add(user.userId());
            }
        }
        return new ReportData(
                anchor,
                List.of(users),
                dates,
                appOpened,
                experiment == null ? null : new ExperimentData(Duration.ofHours(2), experiment));
    }
}
