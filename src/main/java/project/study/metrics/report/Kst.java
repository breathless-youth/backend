package project.study.metrics.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 리포트의 날짜·시각은 모두 KST다. */
public final class Kst {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Kst() {}

    public static LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    public static int hour(Instant instant) {
        return instant.atZone(ZONE).getHour();
    }

    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** 3시간 구간 라벨 — 21 → "21~24시". */
    public static String bandLabel(int startHour) {
        return "%02d~%02d시".formatted(startHour, startHour + 3);
    }
}
