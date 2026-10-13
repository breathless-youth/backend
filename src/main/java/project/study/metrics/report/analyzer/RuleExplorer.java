package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.CohortStats;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 🔍 자동 탐색 (스펙 §4.5, §6) — 코호트 C에 규칙 40개를 적용해 노출 조건을 통과한 것을 phi 내림차순 Top 3로. 같은 계산을
 * A−1·A−2로도 돌려 연속 일수를 붙인다(우연히 높은 규칙을 거르는 두 번째 거름망).
 */
public final class RuleExplorer {

    static final int TOP = 3;
    static final int TREND_DAYS_BACK = 2;

    // phi 내림차순 → 한 사람 수 내림차순 → 카탈로그 순(안정 정렬)
    private static final Comparator<Result> RANKING = Comparator.comparingDouble(
                    (Result result) -> result.table().phi())
            .reversed()
            .thenComparing(result -> result.table().doers(), Comparator.reverseOrder());

    private RuleExplorer() {}

    record Result(SegmentRule rule, TwoByTwo table, Exposure exposure) {}

    record Evaluation(int cohortSize, int retained, List<Result> results, List<Result> ranked) {
        List<String> topKeys() {
            return ranked.stream().limit(TOP).map(result -> result.rule().key()).toList();
        }
    }

    public static ExplorerSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        Evaluation today = evaluate(data, anchor);
        List<List<String>> previous = IntStream.rangeClosed(1, TREND_DAYS_BACK)
                .mapToObj(back -> evaluate(data, anchor.minusDays(back)).topKeys())
                .toList();
        List<RankedRule> top = new ArrayList<>();
        for (int index = 0; index < Math.min(TOP, today.ranked().size()); index++) {
            Result result = today.ranked().get(index);
            int rank = index + 1;
            top.add(new RankedRule(
                    result.rule(), result.table(), rank, days(result.rule().key(), rank, previous)));
        }
        Map<Exposure, Integer> tally = new EnumMap<>(Exposure.class);
        today.results().forEach(result -> tally.merge(result.exposure(), 1, Integer::sum));
        Exposure fixed = today.results().stream()
                .filter(result -> RuleCatalog.fixed(result.rule()))
                .map(Result::exposure)
                .findFirst()
                .orElseThrow();
        return new ExplorerSection(
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                today.cohortSize(),
                today.retained(),
                top,
                tally,
                fixed);
    }

    static Evaluation evaluate(ReportData data, LocalDate anchor) {
        List<UserTimeline> cohort = Cohorts.weekTwo(data, anchor);
        CohortStats stats = CohortStats.of(cohort);
        boolean amplitude = Cohorts.firstWeeksCollected(data, anchor);
        List<Result> results = RuleCatalog.all().stream()
                .map(rule -> result(rule, cohort, stats, amplitude))
                .toList();
        List<Result> ranked = results.stream()
                .filter(result -> result.exposure() == Exposure.PASS)
                .sorted(RANKING)
                .toList();
        int retained = (int) cohort.stream().filter(Cohorts::retainedWeekTwo).count();
        return new Evaluation(cohort.size(), retained, results, ranked);
    }

    private static Result result(SegmentRule rule, List<UserTimeline> cohort, CohortStats stats, boolean amplitude) {
        if (rule.amplitude() && !amplitude) {
            return new Result(rule, null, Exposure.NOT_COLLECTED);
        }
        TwoByTwo table = TwoByTwo.of(cohort, user -> rule.matches(user, stats), Cohorts::retainedWeekTwo);
        return new Result(rule, table, table.exposure());
    }

    /** 오늘 포함 연속 일수. 1위면 그 전 날들도 1위였는지, 2~3위면 Top 3에 들었는지를 A−1부터 거꾸로 본다. */
    static int days(String key, int rank, List<List<String>> previousTops) {
        int days = 1;
        for (List<String> tops : previousTops) {
            boolean kept = rank == 1 ? !tops.isEmpty() && tops.getFirst().equals(key) : tops.contains(key);
            if (!kept) {
                break;
            }
            days++;
        }
        return days;
    }
}
