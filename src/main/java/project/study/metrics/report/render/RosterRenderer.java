package project.study.metrics.report.render;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Kst;
import project.study.metrics.report.analyzer.RosterCard;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

/** 🙋 명단 스레드 (부록 A 스레드 4). */
public final class RosterRenderer {

    private RosterRenderer() {}

    public static String render(RosterSection section) {
        String range = Fmt.range(section.cohortFrom(), section.cohortTo());
        if (section.total() == 0) {
            return "🙋 첫 주에 3일 이상 공부한 사람 없음 (가입 %s)".formatted(range);
        }
        List<String> lines = new ArrayList<>();
        lines.add("🙋 첫 주에 3일 이상 공부한 %d명 (가입 %s)".formatted(section.total(), range));
        lines.add("🆕 = 어제로 둘째 주가 끝나 새로 들어온 사람(%s 가입) · \"한 일\"은 첫 주 기준".formatted(Fmt.monthDay(section.cohortTo())));
        lines.add("");
        group(lines, "✅ 둘째 주에도 공부한 %d명", section.retained(), section.anchor());
        lines.add("");
        group(lines, "❌ 둘째 주에 안 온 %d명", section.dropped(), section.anchor());
        return String.join("\n", lines);
    }

    static List<String> card(RosterCard card, LocalDate anchor) {
        return List.of(
                who(card),
                "   공부: " + flow(card.flow(), anchor),
                "   첫 주에 한 일: " + actions(card),
                "   습관: " + habits(card.habits()));
    }

    private static void group(List<String> lines, String title, RosterGroup group, LocalDate anchor) {
        lines.add(title.formatted(group.total()));
        group.cards().forEach(card -> lines.addAll(card(card, anchor)));
        if (group.omitted() > 0) {
            lines.add("⋯ 외 %d명 (가입 %s 이전)".formatted(group.omitted(), Fmt.monthDay(group.omittedSignupOnOrBefore())));
        }
    }

    private static String who(RosterCard card) {
        List<String> parts = new ArrayList<>();
        parts.add((card.fresh() ? "🆕 " : "") + card.nickname() + "(#" + card.userId() + ")");
        parts.add(Fmt.monthDayWeekday(Kst.date(card.signupAt())) + " " + Kst.hour(card.signupAt()) + "시 가입");
        parts.add(card.profile());
        if (card.os() != null) {
            parts.add(card.os());
        }
        parts.add(card.attribution());
        return String.join(" · ", parts);
    }

    private static String flow(StudyFlow flow, LocalDate anchor) {
        String first = "첫 주 %d일·%s(최장 연속 %d일)"
                .formatted(flow.firstWeekDays(), Fmt.duration(flow.firstWeekFocusSec()), flow.firstWeekLongestRun());
        String second = flow.secondWeekDays() == 0
                ? "둘째 주 0일"
                : "둘째 주 %d일·%s".formatted(flow.secondWeekDays(), Fmt.duration(flow.secondWeekFocusSec()));
        String streak = flow.currentStreak() > 0 ? " · 지금 연속 " + Fmt.streak(flow.currentStreak()) : "";
        return first + " → " + second + " → 마지막 공부 " + last(flow, anchor) + streak;
    }

    // 리포트는 A 다음 날 아침에 읽으니 A가 "어제"이고 그 전은 A+1 기준 며칠 전이다
    private static String last(StudyFlow flow, LocalDate anchor) {
        if (flow.lastStudyDay() == null) {
            return "없음";
        }
        if (flow.lastStudyDay().equals(anchor)) {
            return "어제";
        }
        long daysAgo = ChronoUnit.DAYS.between(flow.lastStudyDay(), anchor.plusDays(1));
        return daysAgo + "일 전" + (flow.lastInFirstWeek() ? "(첫 주)" : "");
    }

    private static String actions(RosterCard card) {
        String text = card.actions().isEmpty() ? "기록된 행동 없음" : String.join(" · ", card.actions());
        return card.amplitudePartial() ? text + " (Amplitude 일부 미수집)" : text;
    }

    private static String habits(Habits habits) {
        List<String> parts = new ArrayList<>();
        habits.averageSessionFocusSec().ifPresent(value -> parts.add("세션 평균 " + Fmt.duration((int) Math.round(value))));
        habits.topBandStartHour().ifPresent(hour -> parts.add("주로 " + Kst.bandLabel(hour)));
        habits.socialPercent().ifPresent(percent -> parts.add("소셜 순공 " + percent + "%"));
        habits.pausesPerSession().ifPresent(value -> parts.add("일시정지 세션당 " + Fmt.decimal(value) + "회"));
        habits.distractionsPerHour().ifPresent(value -> parts.add("딴짓 1시간당 " + Fmt.decimal(value) + "회"));
        return parts.isEmpty() ? "기록 없음" : String.join(" · ", parts);
    }
}
