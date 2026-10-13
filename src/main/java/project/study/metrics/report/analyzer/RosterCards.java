package project.study.metrics.report.analyzer;

import static project.study.metrics.report.UserTimeline.Activity.DDAY_SET;
import static project.study.metrics.report.UserTimeline.Activity.ROOM_JOIN;
import static project.study.metrics.report.UserTimeline.Activity.SUBJECT_CREATED;
import static project.study.metrics.report.UserTimeline.Activity.TASK_DONE;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Attributions;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.FirstWeekHabits;
import project.study.metrics.report.OsLabels;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.studysession.StudySessionThresholds;

/** 유저 한 명 → 명단 카드. */
final class RosterCards {

    // 이름만 쓰는 Amplitude 행동 — 표시 순서 그대로
    private static final List<Map.Entry<String, String>> AMPLITUDE_ACTIONS = List.of(
            Map.entry("간단 모드", AmplitudeKeys.SIMPLE_MODE_ON),
            Map.entry("가로 화면", AmplitudeKeys.LANDSCAPE),
            Map.entry("카메라", AmplitudeKeys.CAMERA_ON),
            Map.entry("백색소음", AmplitudeKeys.AMBIENT_SOUND),
            Map.entry("플래너", AmplitudeKeys.PLANNER),
            Map.entry("랭킹", AmplitudeKeys.RANKING),
            Map.entry("초대 공유", AmplitudeKeys.INVITE_SHARED),
            Map.entry("가이드 완료", AmplitudeKeys.GUIDE_FINISHED),
            Map.entry("기록 날짜 보기", AmplitudeKeys.RECORDS_DATE),
            Map.entry("푸시 열기", AmplitudeKeys.PUSH_OPENED));
    private static final int HOURS_PER_BAND = 3;

    private RosterCards() {}

    static RosterCard of(ReportData data, UserTimeline user) {
        LocalDate anchor = data.anchor();
        boolean amplitude = data.amplitudeCollected(user.day(0), user.day(6));
        return new RosterCard(
                user.signupDate().equals(Cohorts.weekTwoTo(anchor)),
                user.userId(),
                Profiles.nickname(user),
                user.signupAt(),
                Profiles.label(user),
                user.attribute()
                        .map(attribute -> OsLabels.label(attribute.osName()))
                        .orElse(null),
                Attributions.label(user),
                flow(user, anchor),
                actions(user),
                !amplitude,
                habits(user, amplitude));
    }

    private static StudyFlow flow(UserTimeline user, LocalDate anchor) {
        LocalDate last = user.lastStudyDay(anchor).orElse(null);
        return new StudyFlow(
                Cohorts.firstWeekStudyDays(user),
                user.focusSec(user.day(0), user.day(6)),
                user.longestRun(user.day(0), user.day(6), StudySessionThresholds.MIN_STREAK_FOCUS_SEC),
                user.studyDays(user.day(7), user.day(13)),
                user.focusSec(user.day(7), user.day(13)),
                last,
                last != null && !last.isAfter(user.day(6)),
                user.streakEndingAt(anchor));
    }

    static List<String> actions(UserTimeline user) {
        LocalDate from = user.day(0);
        LocalDate to = user.day(6);
        List<String> actions = new ArrayList<>();
        counted(actions, "소셜 방 %d회", user.activityCount(ROOM_JOIN, from, to));
        counted(actions, "과목 %d개", user.activityCount(SUBJECT_CREATED, from, to));
        counted(actions, "할 일 완료 %d개", user.activityCount(TASK_DONE, from, to));
        if (user.activityCount(DDAY_SET, from, to) > 0) {
            actions.add("D-day");
        }
        for (Map.Entry<String, String> action : AMPLITUDE_ACTIONS) {
            if (user.amplitudeCount(action.getValue(), from, to) > 0) {
                actions.add(action.getKey());
            }
        }
        counted(actions, "앱 실행 %d일", user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, from, to));
        return actions;
    }

    private static void counted(List<String> actions, String format, int count) {
        if (count > 0) {
            actions.add(format.formatted(count));
        }
    }

    // 딴짓은 Amplitude 값이라 첫 주가 다 수집됐을 때만 쓴다 — 빠진 날을 0으로 세면 "딴짓 적음"으로 보인다
    private static Habits habits(UserTimeline user, boolean amplitude) {
        OptionalInt band = FirstWeekHabits.topStartBand(user);
        return new Habits(
                FirstWeekHabits.averageSessionFocusSec(user),
                band.isPresent() ? OptionalInt.of(band.getAsInt() * HOURS_PER_BAND) : OptionalInt.empty(),
                FirstWeekHabits.socialFocusPercent(user),
                FirstWeekHabits.pausesPerSession(user),
                amplitude ? FirstWeekHabits.distractionsPerFocusHour(user) : OptionalDouble.empty());
    }
}
