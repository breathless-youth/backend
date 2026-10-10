package project.study.studysession.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TimeSlotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    @Test
    void 구간은_시작을_포함하고_끝을_포함하지_않는다() {
        assertThat(TimeSlot.at(at(10, 4, 0))).isEqualTo(TimeSlot.DAWN);
        assertThat(TimeSlot.at(at(10, 3, 59))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 7, 0))).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.at(at(10, 12, 0))).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.at(at(10, 18, 0))).isEqualTo(TimeSlot.EVENING);
        assertThat(TimeSlot.at(at(10, 22, 0))).isEqualTo(TimeSlot.NIGHT);
    }

    @Test
    void 자정_뒤_4시_전은_전날_심야다() {
        assertThat(TimeSlot.slotDateOf(at(11, 3, 59))).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(TimeSlot.slotDateOf(at(11, 4, 0))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(TimeSlot.slotDateOf(at(10, 23, 0))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void 다음_경계는_밤_10시_뒤면_다음날_4시다() {
        assertThat(TimeSlot.nextBoundary(at(10, 23, 0))).isEqualTo(at(11, 4, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 7, 0))).isEqualTo(at(10, 12, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 0, 0))).isEqualTo(at(10, 4, 0));
    }
}
