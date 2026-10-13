package project.study.metrics.report.analyzer;

import static java.util.stream.Collectors.toSet;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.fact.SessionFact;

/** 🧑‍🎓 어제 공부한 유저를 단계로 나눈다 (스펙 §4.6). */
public final class ActiveUserClassifier {

    private static final int NEW_DAYS = 6;
    private static final int QUIET_DAYS = 14;
    private static final int RECENT_DAYS = 6;
    private static final int STEADY_STUDY_DAYS = 3;
    private static final Comparator<ActiveUserRow> ORDER =
            Comparator.comparingInt(ActiveUserRow::focusSec).reversed().thenComparingLong(ActiveUserRow::userId);

    private ActiveUserClassifier() {}

    public static ActiveUsersSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> active = data.studiedOn(anchor);
        boolean recentApp = data.amplitudeCollected(anchor.minusDays(RECENT_DAYS), anchor);
        Map<Stage, List<ActiveUserRow>> byStage = new EnumMap<>(Stage.class);
        for (UserTimeline user : active) {
            byStage.computeIfAbsent(stage(user, anchor), stage -> new ArrayList<>())
                    .add(row(user, anchor, recentApp));
        }
        byStage.values().forEach(rows -> rows.sort(ORDER));
        Set<Long> studied = active.stream().map(UserTimeline::userId).collect(toSet());
        Integer appOnly = data.amplitudeCollected(anchor, anchor)
                ? (int) data.appOpenedOnAnchor().stream()
                        .filter(id -> !studied.contains(id))
                        .count()
                : null;
        return new ActiveUsersSection(active.size(), byStage, appOnly);
    }

    /** 위에서 먼저 맞는 하나 — 새로 온 사람 → 돌아온 사람 → 꾸준한 사람 → 가끔 오는 사람. */
    static Stage stage(UserTimeline user, LocalDate anchor) {
        if (!user.signupDate().isBefore(anchor.minusDays(NEW_DAYS))) {
            return Stage.NEW;
        }
        if (user.studyDays(anchor.minusDays(QUIET_DAYS), anchor.minusDays(1)) == 0) {
            return Stage.RETURNING;
        }
        if (user.studyDays(anchor.minusDays(RECENT_DAYS), anchor) >= STEADY_STUDY_DAYS) {
            return Stage.STEADY;
        }
        return Stage.OCCASIONAL;
    }

    private static ActiveUserRow row(UserTimeline user, LocalDate anchor, boolean recentApp) {
        int focus = user.focusSec(anchor);
        int social = user.sessions(anchor, anchor).stream()
                .filter(SessionFact::social)
                .mapToInt(SessionFact::focusSec)
                .sum();
        Integer appDays = recentApp
                ? user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, anchor.minusDays(RECENT_DAYS), anchor)
                : null;
        return new ActiveUserRow(
                user.userId(),
                Profiles.nickname(user),
                ChronoUnit.DAYS.between(user.signupDate(), anchor),
                user.streakEndingAt(anchor),
                focus,
                social * 2 >= focus,
                user.liveSubjectsAt(Kst.startOf(anchor.plusDays(1))),
                Profiles.label(user),
                appDays);
    }
}
