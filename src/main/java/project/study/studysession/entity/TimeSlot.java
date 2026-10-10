package project.study.studysession.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 시간대 랭킹의 구간 (BY-828). 경계는 KST 04·07·12·18·22시이고 구간은 [시작, 끝) 반개구간이다.
 * 심야(22–04)만 시작한 날에 귀속한다 — D일 22:00 ~ D+1일 04:00이 D일 심야다.
 */
public enum TimeSlot {
    DAWN,
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int[] BOUNDARY_HOURS = {4, 7, 12, 18, 22};

    /** 시각이 속한 구간. */
    public static TimeSlot at(Instant instant) {
        int hour = instant.atZone(KST).getHour();
        if (hour < 4 || hour >= 22) {
            return NIGHT;
        }
        if (hour < 7) {
            return DAWN;
        }
        if (hour < 12) {
            return MORNING;
        }
        return hour < 18 ? AFTERNOON : EVENING;
    }

    /** 시각이 속한 구간의 귀속 날짜 — 심야의 00~04시는 전날이다. */
    public static LocalDate slotDateOf(Instant instant) {
        ZonedDateTime kst = instant.atZone(KST);
        return kst.getHour() < 4 ? kst.toLocalDate().minusDays(1) : kst.toLocalDate();
    }

    /** instant보다 뒤인 가장 가까운 구간 경계. */
    public static Instant nextBoundary(Instant instant) {
        LocalDate date = instant.atZone(KST).toLocalDate();
        for (int hour : BOUNDARY_HOURS) {
            Instant boundary = date.atTime(hour, 0).atZone(KST).toInstant();
            if (boundary.isAfter(instant)) {
                return boundary;
            }
        }
        return date.plusDays(1).atTime(BOUNDARY_HOURS[0], 0).atZone(KST).toInstant();
    }
}
