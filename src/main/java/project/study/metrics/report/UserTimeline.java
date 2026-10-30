package project.study.metrics.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserFact;
import project.study.studysession.StudySessionThresholds;

/**
 * 유저 한 명의 리포트 기간 사실(일별 순공·공부한 날·세션·활동·Amplitude 횟수·속성). 분석기는 이 객체에만 묻는다.
 *
 * <p>공부한 날은 세션 단위로 판정한다 — 자정 분할 조각은 각각 5분을 넘어야 한다(ADR-0012 결정 3, 앱 스트릭과 같은 잣대).
 */
public final class UserTimeline {

    public enum Activity {
        ROOM_JOIN,
        TASK_DONE,
        DDAY_SET,
        SUBJECT_CREATED
    }

    private final UserFact user;
    private final LocalDate signupDate;
    private final List<SessionFact> sessions;
    private final Map<LocalDate, Integer> focusByDate = new HashMap<>();
    private final Set<LocalDate> studyDates = new HashSet<>();
    private final Map<Activity, List<LocalDate>> activityDates = new EnumMap<>(Activity.class);
    private final List<SubjectFact> subjects;
    private final Map<String, Map<LocalDate, Integer>> amplitude = new HashMap<>();
    private final AmplitudeAttribute attribute;

    public UserTimeline(
            UserFact user,
            List<SessionFact> sessions,
            Map<Activity, List<Instant>> activities,
            List<SubjectFact> subjects,
            List<AmplitudeDailyCount> amplitudeCounts,
            AmplitudeAttribute attribute) {
        this.user = user;
        this.signupDate = Kst.date(user.createdAt());
        this.sessions = sessions.stream()
                .sorted(Comparator.comparing(SessionFact::startedAt))
                .toList();
        for (SessionFact session : this.sessions) {
            focusByDate.merge(session.statDate(), session.focusSec(), Integer::sum);
            if (session.focusSec() >= StudySessionThresholds.MIN_STREAK_FOCUS_SEC) {
                studyDates.add(session.statDate());
            }
        }
        activities.forEach((activity, times) ->
                activityDates.put(activity, times.stream().map(Kst::date).toList()));
        this.subjects = List.copyOf(subjects);
        activityDates.put(
                Activity.SUBJECT_CREATED,
                subjects.stream().map(SubjectFact::createdAt).map(Kst::date).toList());
        for (AmplitudeDailyCount count : amplitudeCounts) {
            amplitude
                    .computeIfAbsent(count.key(), key -> new HashMap<>())
                    .merge(count.date(), count.count(), Integer::sum);
        }
        this.attribute = attribute;
    }

    public long userId() {
        return user.userId();
    }

    public String nickname() {
        return user.nickname();
    }

    public Instant signupAt() {
        return user.createdAt();
    }

    public LocalDate signupDate() {
        return signupDate;
    }

    public String category() {
        return user.category();
    }

    public String goal() {
        return user.goal();
    }

    public Optional<AmplitudeAttribute> attribute() {
        return Optional.ofNullable(attribute);
    }

    /** Dn = 가입일 + n일. */
    public LocalDate day(int n) {
        return signupDate.plusDays(n);
    }

    public boolean studied(LocalDate date) {
        return studyDates.contains(date);
    }

    public int focusSec(LocalDate date) {
        return focusByDate.getOrDefault(date, 0);
    }

    public int focusSec(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).mapToInt(this::focusSec).sum();
    }

    public int studyDays(LocalDate from, LocalDate to) {
        return (int) from.datesUntil(to.plusDays(1)).filter(this::studied).count();
    }

    /** 공부한 날이면서 하루 순공이 {@code minDailyFocusSec} 이상인 날이 이어진 가장 긴 일수. */
    public int longestRun(LocalDate from, LocalDate to, int minDailyFocusSec) {
        int longest = 0;
        int current = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            current = studied(date) && focusSec(date) >= minDailyFocusSec ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    /** {@code date}부터 거꾸로 센 연속 공부한 날. 그날 공부를 안 했으면 0. */
    public int streakEndingAt(LocalDate date) {
        int streak = 0;
        for (LocalDate day = date; studied(day); day = day.minusDays(1)) {
            streak++;
        }
        return streak;
    }

    public Optional<LocalDate> lastStudyDay(LocalDate onOrBefore) {
        return studyDates.stream().filter(date -> !date.isAfter(onOrBefore)).max(Comparator.naturalOrder());
    }

    public List<SessionFact> sessions(LocalDate from, LocalDate to) {
        return sessions.stream()
                .filter(session -> !session.statDate().isBefore(from)
                        && !session.statDate().isAfter(to))
                .toList();
    }

    public int activityCount(Activity activity, LocalDate from, LocalDate to) {
        return (int) activityDates.getOrDefault(activity, List.of()).stream()
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .count();
    }

    public int amplitudeCount(String key, LocalDate from, LocalDate to) {
        return amplitude.getOrDefault(key, Map.of()).entrySet().stream()
                .filter(entry ->
                        !entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    public int amplitudeDays(String key, LocalDate from, LocalDate to) {
        return (int) amplitude.getOrDefault(key, Map.of()).entrySet().stream()
                .filter(entry ->
                        !entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .filter(entry -> entry.getValue() > 0)
                .count();
    }

    /** {@code at} 시각에 살아 있던(만들어졌고 아직 안 지운) 과목 수. */
    public int liveSubjectsAt(Instant at) {
        return (int) subjects.stream()
                .filter(subject -> !subject.createdAt().isAfter(at))
                .filter(subject ->
                        subject.deletedAt() == null || subject.deletedAt().isAfter(at))
                .count();
    }

    /** 가입 시각부터 {@code window} 안에 시작한 세션들의 순공 합 — "가입하고 바로 공부"(스펙 §4.1). */
    public int focusStartedWithin(Duration window) {
        Instant until = signupAt().plus(window);
        return sessions.stream()
                .filter(session -> !session.startedAt().isBefore(signupAt())
                        && session.startedAt().isBefore(until))
                .mapToInt(SessionFact::focusSec)
                .sum();
    }
}
