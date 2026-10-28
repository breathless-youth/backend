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
        assertThat(TimeSlot.at(at(10, 0, 0))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 5, 59))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 6, 0))).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.at(at(10, 12, 0))).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.at(at(10, 18, 0))).isEqualTo(TimeSlot.EVENING);
        assertThat(TimeSlot.at(at(10, 23, 59))).isEqualTo(TimeSlot.EVENING);
    }

    @Test
    void 귀속_날짜는_KST_달력_날짜다() {
        assertThat(TimeSlot.slotDateOf(at(11, 0, 0))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(TimeSlot.slotDateOf(at(10, 23, 59))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void 다음_경계는_6시간_단위이고_18시_뒤면_다음날_0시다() {
        assertThat(TimeSlot.nextBoundary(at(10, 0, 0))).isEqualTo(at(10, 6, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 7, 0))).isEqualTo(at(10, 12, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 23, 0))).isEqualTo(at(11, 0, 0));
    }

    @Test
    void 마감하는_구간은_넷이고_DAWN은_어느_시각에도_나오지_않는다() {
        assertThat(TimeSlot.LIVE)
                .containsExactly(TimeSlot.NIGHT, TimeSlot.MORNING, TimeSlot.AFTERNOON, TimeSlot.EVENING);
        for (int hour = 0; hour < 24; hour++) {
            assertThat(TimeSlot.at(at(10, hour, 0))).isNotEqualTo(TimeSlot.DAWN);
        }
    }
}
