package project.study.studysession.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 시간대 랭킹의 구간 (BY-828, BY-1046). 경계는 KST 0·6·12·18시이고 구간은 [시작, 끝) 반개구간이다.
 * DAWN(옛 04–07시)은 지난 마감 기록의 board_key를 읽으려고만 남긴다 — 어떤 시각도 DAWN이 아니고 DAWN 판도 없다.
 */
public enum TimeSlot {
    NIGHT,
    MORNING,
    AFTERNOON,
    EVENING,
    DAWN;

    /** 판이 있는 구간, 하루 순서. */
    public static final List<TimeSlot> LIVE = List.of(NIGHT, MORNING, AFTERNOON, EVENING);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int[] BOUNDARY_HOURS = {0, 6, 12, 18};

    /** 시각이 속한 구간. */
    public static TimeSlot at(Instant instant) {
        return LIVE.get(instant.atZone(KST).getHour() / 6);
    }

    /** 시각이 속한 구간의 귀속 날짜 — 모든 구간이 하루 안에 있어 KST 달력 날짜다. */
    public static LocalDate slotDateOf(Instant instant) {
        return instant.atZone(KST).toLocalDate();
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
        return date.plusDays(1).atStartOfDay(KST).toInstant();
    }
}
