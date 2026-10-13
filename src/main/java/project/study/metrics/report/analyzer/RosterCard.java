package project.study.metrics.report.analyzer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * 명단 카드 한 장 (스펙 §4.4) — 누구 · 공부 흐름 · 첫 주에 한 일 · 습관.
 *
 * @param fresh 어제로 둘째 주가 끝나 새로 들어온 사람(가입일 A−13)
 * @param os OS 라벨, 모르면 null
 * @param actions 첫 주에 한 행동(한 것만), 순서 고정
 * @param amplitudePartial 첫 주에 Amplitude 미수집일이 있다
 */
public record RosterCard(
        boolean fresh,
        long userId,
        String nickname,
        Instant signupAt,
        String profile,
        String os,
        String attribution,
        StudyFlow flow,
        List<String> actions,
        boolean amplitudePartial,
        Habits habits) {

    /**
     * @param lastStudyDay A까지의 마지막 공부한 날, 없으면 null
     * @param lastInFirstWeek 마지막 공부한 날이 첫 주 안이다
     * @param currentStreak A부터 거꾸로 센 연속 공부한 날
     */
    public record StudyFlow(
            int firstWeekDays,
            int firstWeekFocusSec,
            int firstWeekLongestRun,
            int secondWeekDays,
            int secondWeekFocusSec,
            LocalDate lastStudyDay,
            boolean lastInFirstWeek,
            int currentStreak) {}

    public record Habits(
            OptionalDouble averageSessionFocusSec,
            OptionalInt topBandStartHour,
            OptionalInt socialPercent,
            OptionalDouble pausesPerSession,
            OptionalDouble distractionsPerHour) {}
}
