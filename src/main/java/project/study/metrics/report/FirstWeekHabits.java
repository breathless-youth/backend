package project.study.metrics.report;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.StudySessionThresholds;

/**
 * 첫 주(D0~D6) 공부 습관 값 — 규칙 묶음 F와 명단 카드의 "습관" 줄이 같은 계산을 쓴다.
 *
 * <p>세션 단위 값은 앱 기록 목록과 같은 잣대로 순공 1분 이상 세션만 센다(잘못 눌렀다 끈 세션이 평균을 깎지 않게).
 */
public final class FirstWeekHabits {

    public static final Set<Integer> NIGHT_HOURS = Set.of(21, 22, 23, 0, 1, 2);
    public static final Set<Integer> MORNING_HOURS = Set.of(5, 6, 7, 8);

    private static final int BANDS = 8;
    private static final int HOURS_PER_BAND = 3;
    private static final double SECONDS_PER_HOUR = 3600.0;

    private FirstWeekHabits() {}

    public static List<SessionFact> sessions(UserTimeline user) {
        return user.sessions(user.day(0), user.day(6)).stream()
                .filter(session -> session.focusSec() >= StudySessionThresholds.MIN_LIST_FOCUS_SEC)
                .toList();
    }

    public static OptionalDouble pausesPerSession(UserTimeline user) {
        return sessions(user).stream().mapToInt(SessionFact::pauseCount).average();
    }

    public static OptionalDouble averageSessionFocusSec(UserTimeline user) {
        return sessions(user).stream().mapToInt(SessionFact::focusSec).average();
    }

    /** 순공 1시간당 딴짓 이벤트 수(Amplitude). 첫 주 순공이 없으면 값이 없다. */
    public static OptionalDouble distractionsPerFocusHour(UserTimeline user) {
        int focusSec = user.focusSec(user.day(0), user.day(6));
        if (focusSec <= 0) {
            return OptionalDouble.empty();
        }
        int distractions = user.amplitudeCount(AmplitudeKeys.DISTRACTED, user.day(0), user.day(6));
        return OptionalDouble.of(distractions * SECONDS_PER_HOUR / focusSec);
    }

    /** 순공 중 KST 시작 시각이 {@code hours}에 드는 몫(0~1). 순공이 없으면 0. */
    public static double focusShareStartedIn(UserTimeline user, Set<Integer> hours) {
        List<SessionFact> sessions = sessions(user);
        int total = sessions.stream().mapToInt(SessionFact::focusSec).sum();
        if (total == 0) {
            return 0;
        }
        int inHours = sessions.stream()
                .filter(session -> hours.contains(Kst.hour(session.startedAt())))
                .mapToInt(SessionFact::focusSec)
                .sum();
        return (double) inHours / total;
    }

    /** 순공이 가장 많은 3시간 구간(0 = 00~03시 … 7 = 21~24시). 같으면 이른 구간. */
    public static OptionalInt topStartBand(UserTimeline user) {
        int[] focus = new int[BANDS];
        sessions(user).forEach(session -> focus[Kst.hour(session.startedAt()) / HOURS_PER_BAND] += session.focusSec());
        int best = -1;
        for (int band = 0; band < BANDS; band++) {
            if (focus[band] > 0 && (best < 0 || focus[band] > focus[best])) {
                best = band;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /** 소셜 세션 순공 비율(%). 순공이 없으면 값이 없다. */
    public static OptionalInt socialFocusPercent(UserTimeline user) {
        List<SessionFact> sessions = sessions(user);
        int total = sessions.stream().mapToInt(SessionFact::focusSec).sum();
        if (total == 0) {
            return OptionalInt.empty();
        }
        int social = sessions.stream()
                .filter(SessionFact::social)
                .mapToInt(SessionFact::focusSec)
                .sum();
        return OptionalInt.of((int) Math.round(100.0 * social / total));
    }
}
