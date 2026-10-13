package project.study.metrics.report.rule;

/** 규칙 카탈로그 묶음 A~I (스펙 §5). 선언 순서가 카탈로그 순서다. */
public enum RuleGroup {
    STREAK,
    STUDY_DAYS,
    FIRST_DAY,
    FEATURE,
    APP_EVENT,
    HABIT,
    SCREEN,
    INVITE,
    RECORD_PUSH;

    /** 묶음 D~I — 공부량이 아니라 행동이라 "주로 한 행동" 후보다. */
    public boolean behavior() {
        return compareTo(FEATURE) >= 0;
    }
}
