package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

/** 🙋 첫 주 3일 이상 명단 (스펙 §4.4). 묶음 안은 가입 최신 순 — 매일은 맨 위 🆕만 보면 된다. */
public final class SegmentRoster {

    public static final int CARD_LIMIT = 30;

    private static final Comparator<UserTimeline> NEWEST_FIRST =
            Comparator.comparing(UserTimeline::signupAt).reversed().thenComparing(UserTimeline::userId);

    private SegmentRoster() {}

    public static RosterSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> segment = Cohorts.weekTwo(data, anchor).stream()
                .filter(Cohorts::fixedSegment)
                .sorted(NEWEST_FIRST)
                .toList();
        return new RosterSection(
                anchor,
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                group(data, segment.stream().filter(Cohorts::retainedWeekTwo).toList()),
                group(
                        data,
                        segment.stream()
                                .filter(user -> !Cohorts.retainedWeekTwo(user))
                                .toList()));
    }

    private static RosterGroup group(ReportData data, List<UserTimeline> users) {
        List<RosterCard> cards = users.stream()
                .limit(CARD_LIMIT)
                .map(user -> RosterCards.of(data, user))
                .toList();
        LocalDate omittedFrom =
                users.size() > CARD_LIMIT ? users.get(CARD_LIMIT).signupDate() : null;
        return new RosterGroup(users.size(), cards, omittedFrom);
    }
}
