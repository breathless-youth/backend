package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.rule.CohortStats;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/** 👥 첫 주 공부 일수별 비교 (스펙 §4.3). */
public final class SegmentAnalyzer {

    static final int MIN_SEGMENT = 5;
    static final int MIN_BEHAVIOR_PERCENT = 30;
    static final int BEHAVIOR_LIMIT = 5;

    private SegmentAnalyzer() {}

    public static SegmentSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> cohort = Cohorts.weekTwo(data, anchor);
        List<UserTimeline> segment =
                cohort.stream().filter(Cohorts::fixedSegment).toList();
        List<UserTimeline> rest =
                cohort.stream().filter(user -> !Cohorts.fixedSegment(user)).toList();
        boolean amplitude = Cohorts.firstWeeksCollected(data, anchor);
        boolean enough = segment.size() >= MIN_SEGMENT;
        return new SegmentSection(
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                cohort.size(),
                groups(cohort),
                TwoByTwo.of(cohort, Cohorts::fixedSegment, Cohorts::retainedWeekTwo),
                enough,
                amplitude,
                enough ? behaviors(cohort, segment, rest, amplitude) : List.of(),
                new BehaviorRow("프로필(분야·목표) 입력", rate(segment, Profiles::filled), rate(rest, Profiles::filled)),
                enough ? SegmentProfiles.compare(segment, rest) : List.of());
    }

    static Rate rate(List<UserTimeline> users, Predicate<UserTimeline> test) {
        return Rate.of((int) users.stream().filter(test).count(), users.size());
    }

    private static List<GroupRow> groups(List<UserTimeline> cohort) {
        return List.of(
                group("3일 이상", cohort, days -> days >= Cohorts.FIXED_SEGMENT_DAYS),
                group("1~2일", cohort, days -> days >= 1 && days < Cohorts.FIXED_SEGMENT_DAYS),
                group("0일", cohort, days -> days == 0));
    }

    private static GroupRow group(String label, List<UserTimeline> cohort, IntPredicate firstWeekDays) {
        List<UserTimeline> members = cohort.stream()
                .filter(user -> firstWeekDays.test(Cohorts.firstWeekStudyDays(user)))
                .toList();
        return new GroupRow(label, members.size(), rate(members, Cohorts::retainedWeekTwo));
    }

    // 3일 이상의 30% 이상이 한 행동 중 (3일 이상 사용률 − 나머지 사용률)이 큰 순. 같으면 카탈로그 순(안정 정렬)
    private static List<BehaviorRow> behaviors(
            List<UserTimeline> cohort, List<UserTimeline> segment, List<UserTimeline> rest, boolean amplitude) {
        CohortStats stats = CohortStats.of(cohort);
        return RuleCatalog.behaviors().stream()
                .filter(rule -> amplitude || !rule.amplitude())
                .map(rule -> row(rule, stats, segment, rest))
                .filter(row -> row.segment().atLeastPercent(MIN_BEHAVIOR_PERCENT))
                .sorted(Comparator.comparingDouble((BehaviorRow row) ->
                                row.segment().ratio() - row.rest().ratio())
                        .reversed())
                .limit(BEHAVIOR_LIMIT)
                .toList();
    }

    private static BehaviorRow row(
            SegmentRule rule, CohortStats stats, List<UserTimeline> segment, List<UserTimeline> rest) {
        Predicate<UserTimeline> did = user -> rule.matches(user, stats);
        return new BehaviorRow(rule.phrase(), rate(segment, did), rate(rest, did));
    }
}
