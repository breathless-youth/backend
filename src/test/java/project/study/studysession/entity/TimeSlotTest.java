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
        assertThat(TimeSlot.at(at(10, 0, 0), KST)).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 5, 59), KST)).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 6, 0), KST)).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.at(at(10, 12, 0), KST)).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.at(at(10, 18, 0), KST)).isEqualTo(TimeSlot.EVENING);
        assertThat(TimeSlot.at(at(10, 23, 59), KST)).isEqualTo(TimeSlot.EVENING);
    }

    @Test
    void 귀속_날짜는_KST_달력_날짜다() {
        assertThat(TimeSlot.slotDateOf(at(11, 0, 0), KST)).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(TimeSlot.slotDateOf(at(10, 23, 59), KST)).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void 다음_경계는_6시간_단위이고_18시_뒤면_다음날_0시다() {
        assertThat(TimeSlot.nextBoundary(at(10, 0, 0), KST)).isEqualTo(at(10, 6, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 7, 0), KST)).isEqualTo(at(10, 12, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 23, 0), KST)).isEqualTo(at(11, 0, 0));
    }

    @Test
    void 구간과_귀속_날짜는_주어진_시간대의_현지_시각으로_정한다() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 서울 10일 13:00 = 뉴욕 10일 00:00(EDT)
        Instant instant = at(10, 13, 0);

        assertThat(TimeSlot.at(instant, newYork)).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.slotDateOf(instant, newYork)).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(TimeSlot.nextBoundary(instant, newYork))
                .isEqualTo(ZonedDateTime.of(2026, 10, 10, 6, 0, 0, 0, newYork).toInstant());
    }

    @Test
    void 서머타임이_끝나_25시간인_날에도_경계는_현지_0_6_12_18시다() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant midnight = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, newYork).toInstant();

        Instant six = TimeSlot.nextBoundary(midnight, newYork);

        // 01시가 두 번 있어 0시에서 6시까지 7시간이다
        assertThat(six).isEqualTo(midnight.plusSeconds(7 * 3600));
        assertThat(TimeSlot.at(six, newYork)).isEqualTo(TimeSlot.MORNING);
    }

    @Test
    void 마감하는_구간은_넷이고_DAWN은_어느_시각에도_나오지_않는다() {
        assertThat(TimeSlot.LIVE)
                .containsExactly(TimeSlot.NIGHT, TimeSlot.MORNING, TimeSlot.AFTERNOON, TimeSlot.EVENING);
        for (int hour = 0; hour < 24; hour++) {
            assertThat(TimeSlot.at(at(10, hour, 0), KST)).isNotEqualTo(TimeSlot.DAWN);
        }
    }
}
