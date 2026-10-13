package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleSource.AMPLITUDE;
import static project.study.metrics.report.rule.RuleSource.SERVER;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_TWO_DAYS;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;

import java.util.List;
import java.util.function.Predicate;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;

/** 묶음 D(서버 기능) · E(앱 행동) · G(화면 설정) · H(초대 유입) · I(기록·알림). */
final class ActionRules {

    private ActionRules() {}

    static List<SegmentRule> features() {
        return List.of(
                server("room-1", "소셜 방 1번 이상 참여", user -> firstWeek(user, Activity.ROOM_JOIN) >= 1),
                server("room-3", "소셜 방 3번 이상 참여", user -> firstWeek(user, Activity.ROOM_JOIN) >= 3),
                server("subjects-3", "과목 3개 이상 등록", user -> firstWeek(user, Activity.SUBJECT_CREATED) >= 3),
                server("tasks-3", "할 일 3개 이상 완료", user -> firstWeek(user, Activity.TASK_DONE) >= 3),
                server("dday", "D-day 설정", user -> firstWeek(user, Activity.DDAY_SET) >= 1));
    }

    static List<SegmentRule> appEvents() {
        return List.of(
                new SegmentRule(
                        "app-days-3",
                        RuleGroup.APP_EVENT,
                        AMPLITUDE,
                        FIRST_WEEK,
                        "앱 실행한 날 3일 이상",
                        (user, stats) -> user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, user.day(0), user.day(6)) >= 3),
                event("ambient", RuleGroup.APP_EVENT, AmplitudeKeys.AMBIENT_SOUND, "백색소음 바꿈"),
                event("planner", RuleGroup.APP_EVENT, AmplitudeKeys.PLANNER, "플래너 열어 봄"),
                event("invite-shared", RuleGroup.APP_EVENT, AmplitudeKeys.INVITE_SHARED, "초대 링크 공유"),
                event("guide", RuleGroup.APP_EVENT, AmplitudeKeys.GUIDE_FINISHED, "가이드 끝까지 봄"),
                event("ranking", RuleGroup.APP_EVENT, AmplitudeKeys.RANKING, "랭킹 봄"));
    }

    static List<SegmentRule> screen() {
        return List.of(
                event("simple-mode", RuleGroup.SCREEN, AmplitudeKeys.SIMPLE_MODE_ON, "간단 모드 켬"),
                event("landscape", RuleGroup.SCREEN, AmplitudeKeys.LANDSCAPE, "가로 화면 씀"),
                event("camera", RuleGroup.SCREEN, AmplitudeKeys.CAMERA_ON, "카메라 켬"));
    }

    static List<SegmentRule> invite() {
        return List.of(new SegmentRule(
                "invite-opened",
                RuleGroup.INVITE,
                AMPLITUDE,
                FIRST_TWO_DAYS,
                "초대 링크로 들어옴",
                (user, stats) -> user.amplitudeCount(AmplitudeKeys.INVITE_OPENED, user.day(0), user.day(1)) >= 1));
    }

    static List<SegmentRule> recordPush() {
        return List.of(
                event("records", RuleGroup.RECORD_PUSH, AmplitudeKeys.RECORDS_DATE, "기록 날짜 넘겨 봄"),
                event("push", RuleGroup.RECORD_PUSH, AmplitudeKeys.PUSH_OPENED, "푸시 알림 열어 봄"));
    }

    private static int firstWeek(UserTimeline user, Activity activity) {
        return user.activityCount(activity, user.day(0), user.day(6));
    }

    private static SegmentRule server(String key, String phrase, Predicate<UserTimeline> test) {
        return new SegmentRule(key, RuleGroup.FEATURE, SERVER, FIRST_WEEK, phrase, (user, stats) -> test.test(user));
    }

    private static SegmentRule event(String key, RuleGroup group, String eventKey, String phrase) {
        return new SegmentRule(
                key,
                group,
                AMPLITUDE,
                FIRST_WEEK,
                phrase,
                (user, stats) -> user.amplitudeCount(eventKey, user.day(0), user.day(6)) >= 1);
    }
}
