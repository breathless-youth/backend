package project.study.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import project.study.common.exception.BadRequestException;

class PlannerCalendarTest {

    @ParameterizedTest
    @CsvSource({
        "2030-03-01T19:59:00Z,Asia/Seoul,2030-03-01",
        "2030-03-01T20:00:00Z,Asia/Seoul,2030-03-02",
        "2030-03-01T19:59:00Z,Pacific/Auckland,2030-03-02",
        "2030-03-01T19:59:00Z,America/New_York,2030-03-01",
        "2025-03-09T08:59:00Z,America/New_York,2025-03-08",
        "2025-03-09T09:00:00Z,America/New_York,2025-03-09"
    })
    void 현지_04시59분까지_전날이고_05시부터_새로운_플래너_날짜다(Instant now, String zone, LocalDate expected) {
        assertThat(PlannerCalendar.today(Clock.fixed(now, ZoneOffset.UTC), ZoneId.of(zone)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "2025-03-08,2025-03-08T10:00:00Z,2025-03-09T09:00:00Z,23",
        "2025-11-01,2025-11-01T09:00:00Z,2025-11-02T10:00:00Z,25"
    })
    void DST_날짜의_하루는_고정_24시간이_아닌_현지_05시에서_다음_05시까지다(
            LocalDate date, Instant expectedStart, Instant expectedEnd, long hours) {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant start = PlannerCalendar.start(date, newYork);
        Instant end = PlannerCalendar.start(date.plusDays(1), newYork);

        assertThat(start).isEqualTo(expectedStart);
        assertThat(end).isEqualTo(expectedEnd);
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofHours(hours));
    }

    @Test
    void 조회는_최대_366개의_달력_날짜를_포함하고_역전과_누락을_거절한다() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate lastAllowed = LocalDate.of(2027, 1, 1);

        assertThatCode(() -> PlannerCalendar.range(start, lastAllowed)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PlannerCalendar.range(start, LocalDate.of(2027, 1, 2)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PlannerCalendar.range(lastAllowed, start)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PlannerCalendar.range(null, start)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PlannerCalendar.range(start, null)).isInstanceOf(BadRequestException.class);
    }
}
