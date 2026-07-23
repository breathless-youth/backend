package project.study.studysession.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudySessionTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    private StatusEvent event(EventStatus status, String startedAt, String endedAt) {
        return new StatusEvent(status, Instant.parse(startedAt), Instant.parse(endedAt));
    }

    @Test
    void 세션_시간과_집중_시간을_계산한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:40:00Z"),
                event(EventStatus.AWAY, "2026-07-24T09:00:00Z", "2026-07-24T09:10:00Z"));

        StudySession session = StudySession.create(1L, START, END, events, CLOCK);

        assertThat(session.getSessionSec()).isEqualTo(7200);
        assertThat(session.getFocusSec()).isEqualTo(7200 - 300 - 600 - 600);
        assertThat(session.getUserId()).isEqualTo(1L);
        assertThat(session.getEvents()).hasSize(3);
    }

    @Test
    void 이벤트가_없으면_세션_전체가_집중_시간이다() {
        StudySession session = StudySession.create(1L, START, END, List.of(), CLOCK);

        assertThat(session.getFocusSec()).isEqualTo(session.getSessionSec());
    }

    @Test
    void 통계_날짜는_시작_시각의_한국_날짜를_따른다() {
        // 2026-07-23T16:30:00Z = KST 2026-07-24 01:30
        Instant start = Instant.parse("2026-07-23T16:30:00Z");
        Instant end = Instant.parse("2026-07-23T18:30:00Z");

        StudySession session = StudySession.create(1L, start, end, List.of(), CLOCK);

        assertThat(session.getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 순서가_뒤섞인_이벤트는_시작_시각_기준으로_정렬된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.AWAY, "2026-07-24T09:00:00Z", "2026-07-24T09:10:00Z"),
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"));

        StudySession session = StudySession.create(1L, START, END, events, CLOCK);

        assertThat(session.getEvents().get(0).getStatus()).isEqualTo(EventStatus.DEVICE);
        assertThat(session.getEvents().get(1).getStatus()).isEqualTo(EventStatus.AWAY);
    }

    @Test
    void 이벤트가_맞닿아_이어지는_것은_허용된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:10:00Z"));

        StudySession session = StudySession.create(1L, START, END, events, CLOCK);

        assertThat(session.getFocusSec()).isEqualTo(7200 - 600);
    }

    @Test
    void 세션의_집중률을_계산한다() {
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:40:00Z"));

        StudySession session = StudySession.create(1L, START, END, events, CLOCK);

        // 6600 / 7200 × 100 = 91.66... → 91.7
        assertThat(session.focusRate()).isEqualTo(91.7);
    }

    @Test
    void 집중률은_소수_한_자리로_반올림된다() {
        assertThat(StudySession.focusRate(1, 3)).isEqualTo(33.3);
        assertThat(StudySession.focusRate(2, 3)).isEqualTo(66.7);
        assertThat(StudySession.focusRate(7200, 7200)).isEqualTo(100.0);
        assertThat(StudySession.focusRate(0, 7200)).isEqualTo(0.0);
    }

    @Test
    void 총시간이_0이면_집중률은_0이다() {
        assertThat(StudySession.focusRate(0, 0)).isEqualTo(0.0);
    }

    @Test
    void 종료가_시작보다_빠르거나_같으면_거부한다() {
        assertThatThrownBy(() -> StudySession.create(1L, START, START, List.of(), CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 이십사_시간을_초과하는_세션은_거부한다() {
        Instant start = NOW.minusSeconds(60 * 60 * 25);
        Instant end = start.plusSeconds(60 * 60 * 24 + 1);

        assertThatThrownBy(() -> StudySession.create(1L, start, end, List.of(), CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 종료_시각이_허용_오차를_넘는_미래이면_거부한다() {
        Instant end = NOW.plusSeconds(60 * 6); // now + 6분 (허용 오차 5분 초과)

        assertThatThrownBy(() -> StudySession.create(1L, START, end, List.of(), CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 이벤트의_종료가_시작보다_빠르거나_같으면_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> StudySession.create(1L, START, END, events, CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 세션_구간을_벗어난_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.AWAY, "2026-07-24T09:50:00Z", "2026-07-24T10:10:00Z"));

        assertThatThrownBy(() -> StudySession.create(1L, START, END, events, CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 겹치는_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:15:00Z"));

        assertThatThrownBy(() -> StudySession.create(1L, START, END, events, CLOCK))
                .isInstanceOf(InvalidSessionException.class);
    }
}
