package project.study.metrics.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserFact;

/** 테스트용 — 유저 한 명의 사실을 KST 날짜·시각 단위로 쌓는다. 가입은 기본 12시, 공부는 기본 20시 싱글 세션. */
public final class TimelineBuilder {

    private final long userId;
    private Instant signupAt;
    private String nickname;
    private String category;
    private String goal;
    private final List<SessionFact> sessions = new ArrayList<>();
    private final Map<Activity, List<Instant>> activities = new EnumMap<>(Activity.class);
    private final List<SubjectFact> subjects = new ArrayList<>();
    private final List<AmplitudeDailyCount> counts = new ArrayList<>();
    private AmplitudeAttribute attribute;

    private TimelineBuilder(long userId, Instant signupAt) {
        this.userId = userId;
        this.signupAt = signupAt;
        this.nickname = "유저" + userId;
    }

    public static TimelineBuilder user(long userId, LocalDate signupDate) {
        return new TimelineBuilder(userId, at(signupDate, 12));
    }

    public static Instant at(LocalDate date, int hour) {
        return date.atStartOfDay(Kst.ZONE).toInstant().plus(Duration.ofHours(hour));
    }

    public TimelineBuilder signupHour(int hour) {
        this.signupAt = at(Kst.date(signupAt), hour);
        return this;
    }

    public TimelineBuilder signupAt(Instant instant) {
        this.signupAt = instant;
        return this;
    }

    public TimelineBuilder profile(String nickname, String category, String goal) {
        this.nickname = nickname;
        this.category = category;
        this.goal = goal;
        return this;
    }

    /** 가입 n일 뒤(KST) 20시에 minutes분 순공한 싱글 세션. */
    public TimelineBuilder studyOnDay(int dayOffset, int minutes) {
        LocalDate date = Kst.date(signupAt).plusDays(dayOffset);
        return session(date, 20, minutes, 0, false);
    }

    /** 가입 n일 뒤마다 30분씩. */
    public TimelineBuilder studyOnDays(int... dayOffsets) {
        for (int offset : dayOffsets) {
            studyOnDay(offset, 30);
        }
        return this;
    }

    public TimelineBuilder session(LocalDate statDate, int startHour, int minutes, int pauses, boolean social) {
        sessions.add(new SessionFact(userId, statDate, at(statDate, startHour), minutes * 60, pauses, social));
        return this;
    }

    public TimelineBuilder sessionAt(Instant startedAt, LocalDate statDate, int focusSec) {
        sessions.add(new SessionFact(userId, statDate, startedAt, focusSec, 0, false));
        return this;
    }

    public TimelineBuilder activity(Activity activity, int dayOffset, int times) {
        for (int i = 0; i < times; i++) {
            Instant when = at(Kst.date(signupAt).plusDays(dayOffset), 9).plusSeconds(i);
            activities.computeIfAbsent(activity, key -> new ArrayList<>()).add(when);
        }
        return this;
    }

    public TimelineBuilder subject(int createdDayOffset, Integer deletedDayOffset) {
        LocalDate signupDate = Kst.date(signupAt);
        Instant deletedAt = deletedDayOffset == null ? null : at(signupDate.plusDays(deletedDayOffset), 9);
        subjects.add(new SubjectFact(userId, at(signupDate.plusDays(createdDayOffset), 9), deletedAt));
        return this;
    }

    public TimelineBuilder amp(String key, int dayOffset, int count) {
        counts.add(new AmplitudeDailyCount(userId, Kst.date(signupAt).plusDays(dayOffset), key, count));
        return this;
    }

    public TimelineBuilder ampOn(String key, LocalDate date, int count) {
        counts.add(new AmplitudeDailyCount(userId, date, key, count));
        return this;
    }

    public TimelineBuilder attribute(String utmSource, boolean adClick, String osName) {
        this.attribute = new AmplitudeAttribute(utmSource, adClick, osName);
        return this;
    }

    public UserTimeline build() {
        UserFact fact = new UserFact(userId, nickname, signupAt, category, goal);
        return new UserTimeline(fact, sessions, activities, subjects, counts, attribute);
    }
}
