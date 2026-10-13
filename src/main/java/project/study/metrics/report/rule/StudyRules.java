package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleGroup.FIRST_DAY;
import static project.study.metrics.report.rule.RuleGroup.STREAK;
import static project.study.metrics.report.rule.RuleGroup.STUDY_DAYS;
import static project.study.metrics.report.rule.RuleSource.SERVER;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;
import static project.study.metrics.report.rule.SegmentRule.Period.SIGNUP_DAY;

import java.util.ArrayList;
import java.util.List;

/** 묶음 A(연속 공부) · B(공부한 날 수) · C(첫날) — 서버 세션으로 판정한다. */
final class StudyRules {

    private static final int[] RUN_DAYS = {2, 3, 4};
    private static final int[] RUN_MINUTES = {10, 30, 60, 120};
    private static final int[] STUDY_DAYS_AT_LEAST = {2, 3, 4, 5};
    private static final int[] FIRST_DAY_MINUTES = {30, 60};
    private static final int STUDY_DAY_MINUTES = 10;

    private StudyRules() {}

    static List<SegmentRule> streaks() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int days : RUN_DAYS) {
            for (int minutes : RUN_MINUTES) {
                rules.add(streak(days, minutes));
            }
        }
        return rules;
    }

    // 하루 순공 10분은 "공부한 날" 정의와 같아 "N일 연속 공부"로 줄여 쓴다
    private static SegmentRule streak(int days, int minutes) {
        String phrase = minutes == STUDY_DAY_MINUTES
                ? "%d일 연속 공부".formatted(days)
                : "%d일 연속 하루 순공 %s 이상".formatted(days, minutesLabel(minutes));
        return new SegmentRule(
                "streak-%d-%d".formatted(days, minutes),
                STREAK,
                SERVER,
                FIRST_WEEK,
                phrase,
                (user, stats) -> user.longestRun(user.day(0), user.day(6), minutes * 60) >= days);
    }

    static List<SegmentRule> studyDays() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int atLeast : STUDY_DAYS_AT_LEAST) {
            rules.add(new SegmentRule(
                    "study-days-" + atLeast,
                    STUDY_DAYS,
                    SERVER,
                    FIRST_WEEK,
                    "공부한 날 %d일 이상".formatted(atLeast),
                    (user, stats) -> user.studyDays(user.day(0), user.day(6)) >= atLeast));
        }
        return rules;
    }

    static List<SegmentRule> firstDay() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int minutes : FIRST_DAY_MINUTES) {
            rules.add(new SegmentRule(
                    "first-day-" + minutes,
                    FIRST_DAY,
                    SERVER,
                    SIGNUP_DAY,
                    "순공 %s 이상".formatted(minutesLabel(minutes)),
                    (user, stats) -> user.focusSec(user.day(0)) >= minutes * 60));
        }
        return rules;
    }

    static String minutesLabel(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + "시간" : minutes + "분";
    }
}
