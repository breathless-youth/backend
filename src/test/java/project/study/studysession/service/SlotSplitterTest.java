package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.TimeSlot;

class SlotSplitterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    private static LocalDate date(int day) {
        return LocalDate.of(2026, 10, day);
    }

    @Test
    void 구간_하나_안이면_통째로_그_구간이다() {
        assertThat(SlotSplitter.split(at(10, 9, 0), at(10, 10, 0), 3000, List.of()))
                .containsExactly(new SessionSlot(TimeSlot.MORNING, date(10), 3000));
    }

    @Test
    void 경계를_넘으면_길이_비율로_나눈다() {
        assertThat(SlotSplitter.split(at(10, 5, 30), at(10, 6, 30), 3600, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 1800),
                        new SessionSlot(TimeSlot.MORNING, date(10), 1800));
    }

    @Test
    void 비공부_이벤트_시간은_배분_가중치에서_빠진다() {
        // 05:30~06:30 중 06:00~06:20 PHONE → 이벤트를 뺀 길이는 심야 1800초 · 아침 600초
        List<StatusEvent> events = List.of(new StatusEvent(EventStatus.PHONE, at(10, 6, 0), at(10, 6, 20)));

        assertThat(SlotSplitter.split(at(10, 5, 30), at(10, 6, 30), 2400, events))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 1800),
                        new SessionSlot(TimeSlot.MORNING, date(10), 600));
    }

    @Test
    void 배분_합은_항상_조각_순공과_같다() {
        List<SessionSlot> slots = SlotSplitter.split(at(10, 5, 0), at(10, 13, 0), 10_001, List.of());

        assertThat(slots)
                .extracting(SessionSlot::getSlot)
                .containsExactly(TimeSlot.NIGHT, TimeSlot.MORNING, TimeSlot.AFTERNOON);
        assertThat(slots.stream().mapToInt(SessionSlot::getFocusSec).sum()).isEqualTo(10_001);
    }

    @Test
    void 자정_뒤의_심야는_그날에_귀속된다() {
        assertThat(SlotSplitter.split(at(10, 2, 0), at(10, 7, 0), 18_000, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 14_400),
                        new SessionSlot(TimeSlot.MORNING, date(10), 3600));
    }

    @Test
    void 저녁은_자정까지다() {
        assertThat(SlotSplitter.split(at(10, 17, 0), at(11, 0, 0), 25_200, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.AFTERNOON, date(10), 3600),
                        new SessionSlot(TimeSlot.EVENING, date(10), 21_600));
    }

    @Test
    void 순공이_0이면_행이_없다() {
        assertThat(SlotSplitter.split(at(10, 9, 0), at(10, 10, 0), 0, List.of()))
                .isEmpty();
    }
}
