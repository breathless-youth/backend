package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleGroup.HABIT;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;

import java.util.List;
import java.util.OptionalDouble;
import project.study.metrics.report.FirstWeekHabits;

/** 묶음 F(공부 습관). 중앙값 규칙은 값을 계산할 수 없는 유저(세션·순공 없음)를 "안 함"으로 본다. */
final class HabitRules {

    private static final int LONG_SESSION_SEC = 30 * 60;
    private static final double MAJORITY = 0.5;

    private HabitRules() {}

    static List<SegmentRule> all() {
        return List.of(
                new SegmentRule(
                        "few-pauses",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "세션당 일시정지가 적음(중앙값 이하)",
                        (user, stats) ->
                                atMost(FirstWeekHabits.pausesPerSession(user), stats.medianPausesPerSession())),
                new SegmentRule(
                        "few-distractions",
                        HABIT,
                        RuleSource.AMPLITUDE,
                        FIRST_WEEK,
                        "딴짓이 적음(순공 1시간당, 중앙값 이하)",
                        (user, stats) -> atMost(
                                FirstWeekHabits.distractionsPerFocusHour(user),
                                stats.medianDistractionsPerFocusHour())),
                new SegmentRule(
                        "long-sessions",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "세션 평균 순공 30분 이상",
                        (user, stats) ->
                                FirstWeekHabits.averageSessionFocusSec(user).orElse(0) >= LONG_SESSION_SEC),
                new SegmentRule(
                        "night-study",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "순공 절반 이상을 밤(21~03시)에 시작",
                        (user, stats) ->
                                FirstWeekHabits.focusShareStartedIn(user, FirstWeekHabits.NIGHT_HOURS) >= MAJORITY),
                new SegmentRule(
                        "morning-study",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "순공 절반 이상을 아침(05~09시)에 시작",
                        (user, stats) ->
                                FirstWeekHabits.focusShareStartedIn(user, FirstWeekHabits.MORNING_HOURS) >= MAJORITY));
    }

    // 중앙값이 NaN이면 비교가 false라 "안 함"이 된다
    private static boolean atMost(OptionalDouble value, double median) {
        return value.isPresent() && value.getAsDouble() <= median;
    }
}
