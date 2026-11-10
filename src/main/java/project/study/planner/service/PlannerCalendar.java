package project.study.planner.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ErrorCode;

/** 날짜는 현지 달력 날짜다. DST 날에도 다음 날짜 05시를 별도로 구한다. */
public final class PlannerCalendar {
    private PlannerCalendar() {}

    public static Instant start(LocalDate date, ZoneId zone) {
        return date.atTime(5, 0).atZone(zone).toInstant();
    }

    public static LocalDate today(Clock clock, ZoneId zone) {
        var now = clock.instant().atZone(zone);
        return now.toLocalTime().isBefore(LocalTime.of(5, 0))
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
    }

    public static void checkDate(LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
            throw new BadRequestException("지원하는 날짜 범위는 0001-01-01부터 9999-12-31까지입니다");
        }
    }

    public static void range(LocalDate from, LocalDate to) {
        checkDate(from);
        checkDate(to);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365) {
            throw new BadRequestException("조회 기간은 날짜 오름차순으로 최대 366일입니다");
        }
    }

    public static void editable(LocalDate date, LocalDate today) {
        checkDate(date);
        if (date.isBefore(today)) {
            throw new BadRequestException(ErrorCode.PLANNER_PAST_DAY, "지난 날의 계획은 완료와 이동만 할 수 있습니다");
        }
    }
}
