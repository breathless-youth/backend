package project.study.metrics.report;

import java.time.LocalDate;
import java.util.List;

/** 세그먼트 비교의 코호트 C와 첫 주·둘째 주 판정 (스펙 §4.3). */
public final class Cohorts {

    /** 고정 세그먼트 S1 — 첫 주(D0~D6) 공부한 날 3일 이상. */
    public static final int FIXED_SEGMENT_DAYS = 3;

    private static final int NEWEST_SIGNUP_DAYS_BACK = 13;
    private static final int OLDEST_SIGNUP_DAYS_BACK = 40;
    private static final int LAST_FIRST_WEEK_DAYS_BACK = 7;

    private Cohorts() {}

    /** 코호트 C = 둘째 주까지 끝난 최근 28일치 가입자, 가입일 {@code [A−40, A−13]}. */
    public static LocalDate weekTwoFrom(LocalDate anchor) {
        return anchor.minusDays(OLDEST_SIGNUP_DAYS_BACK);
    }

    public static LocalDate weekTwoTo(LocalDate anchor) {
        return anchor.minusDays(NEWEST_SIGNUP_DAYS_BACK);
    }

    public static List<UserTimeline> weekTwo(ReportData data, LocalDate anchor) {
        return data.signedUpBetween(weekTwoFrom(anchor), weekTwoTo(anchor));
    }

    /** 코호트 C 전원의 첫 주가 걸친 {@code [A−40, A−7]}에 Amplitude가 모두 수집됐는지. */
    public static boolean firstWeeksCollected(ReportData data, LocalDate anchor) {
        return data.amplitudeCollected(weekTwoFrom(anchor), anchor.minusDays(LAST_FIRST_WEEK_DAYS_BACK));
    }

    public static int firstWeekStudyDays(UserTimeline user) {
        return user.studyDays(user.day(0), user.day(6));
    }

    public static boolean fixedSegment(UserTimeline user) {
        return firstWeekStudyDays(user) >= FIXED_SEGMENT_DAYS;
    }

    /** 2주차 리텐션 — 둘째 주(D7~D13)에 공부한 날이 하루라도 있다. */
    public static boolean retainedWeekTwo(UserTimeline user) {
        return user.studyDays(user.day(7), user.day(13)) >= 1;
    }
}
