package project.study.metrics.report.rule;

import java.util.ArrayList;
import java.util.List;

/** 자동 탐색 규칙 40개 (스펙 §5). 순서 = 묶음 A→I, 묶음 안은 파라미터 순 — 순위 동률의 마지막 기준이다. */
public final class RuleCatalog {

    /** 고정 세그먼트 S1과 같은 규칙. 탐색 후보로 남기고 Top 3에 들면 "(고정 세그먼트)"를 붙인다. */
    public static final String FIXED_RULE_KEY = "study-days-3";

    private static final List<SegmentRule> ALL = build();

    private RuleCatalog() {}

    public static List<SegmentRule> all() {
        return ALL;
    }

    /** 묶음 D~I — 고정 세그먼트의 "주로 한 행동" 후보. */
    public static List<SegmentRule> behaviors() {
        return ALL.stream().filter(rule -> rule.group().behavior()).toList();
    }

    public static boolean fixed(SegmentRule rule) {
        return FIXED_RULE_KEY.equals(rule.key());
    }

    private static List<SegmentRule> build() {
        List<SegmentRule> rules = new ArrayList<>();
        rules.addAll(StudyRules.streaks());
        rules.addAll(StudyRules.studyDays());
        rules.addAll(StudyRules.firstDay());
        rules.addAll(ActionRules.features());
        rules.addAll(ActionRules.appEvents());
        rules.addAll(HabitRules.all());
        rules.addAll(ActionRules.screen());
        rules.addAll(ActionRules.invite());
        rules.addAll(ActionRules.recordPush());
        return List.copyOf(rules);
    }
}
