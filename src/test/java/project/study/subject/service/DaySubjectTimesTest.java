package project.study.subject.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 05시 창에 통째로 든 구간은 저장값, 창 경계에 걸친 구간은 잘라 그 조각의 이벤트로 다시 계산한다 (ADR-0038). */
class DaySubjectTimesTest {

    // 하루 창: 2026-10-20 05:00 KST ~ 2026-10-21 05:00 KST
    private static final Instant DAY_START = Instant.parse("2026-10-19T20:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-10-20T20:00:00Z");

    private static StudySession session(
            Instant start, Instant end, List<StatusEvent> events, StudySessionSubjectSegment... segments) {
        StudySession session = new StudySession(1L, LocalDate.of(2026, 10, 20), start, end, 0, 0, events);
        session.attachSubjectSegments(List.of(segments));
        return session;
    }

    @Test
    void 창에_통째로_든_구간은_저장된_파생값을_그대로_더한다() {
        StudySession session = session(
                DAY_START.plusSeconds(3600),
                DAY_START.plusSeconds(10800),
                List.of(new StatusEvent(EventStatus.PHONE, DAY_START.plusSeconds(4000), DAY_START.plusSeconds(4600))),
                new StudySessionSubjectSegment(
                        7L, DAY_START.plusSeconds(3600), DAY_START.plusSeconds(7200), 3600, 3000),
                new StudySessionSubjectSegment(
                        7L, DAY_START.plusSeconds(7200), DAY_START.plusSeconds(10800), 3600, 3600));

        Map<Long, SubjectTimeSum> sums = DaySubjectTimes.sum(List.of(session), DAY_START, DAY_END);

        assertThat(sums).containsOnlyKeys(7L);
        assertThat(sums.get(7L).studySec()).isEqualTo(7200);
        assertThat(sums.get(7L).focusSec()).isEqualTo(6600);
    }

    @Test
    void 창_끝에_걸친_구간은_창_안_조각만_그_조각의_이벤트로_다시_계산한다() {
        // 21일 04:00~06:00 KST 구간(저장값 7200/6000). 창 안은 04:00~05:00 한 시간. PAUSE 04:10~04:20은 둘 다에서, PHONE 05:30~05:40은 창
        // 밖
        Instant segStart = DAY_END.minusSeconds(3600);
        Instant segEnd = DAY_END.plusSeconds(3600);
        StudySession session = session(
                segStart,
                segEnd,
                List.of(
                        new StatusEvent(EventStatus.PAUSE, segStart.plusSeconds(600), segStart.plusSeconds(1200)),
                        new StatusEvent(EventStatus.PHONE, DAY_END.plusSeconds(1800), DAY_END.plusSeconds(2400))),
                new StudySessionSubjectSegment(7L, segStart, segEnd, 7200, 6000));

        Map<Long, SubjectTimeSum> sums = DaySubjectTimes.sum(List.of(session), DAY_START, DAY_END);

        assertThat(sums.get(7L).studySec()).isEqualTo(3000);
        assertThat(sums.get(7L).focusSec()).isEqualTo(3000);
    }

    @Test
    void 창_밖_구간은_세지_않고_다른_과목은_따로_합산한다() {
        StudySession session = session(
                DAY_END,
                DAY_END.plusSeconds(7200),
                List.of(),
                new StudySessionSubjectSegment(7L, DAY_END, DAY_END.plusSeconds(3600), 3600, 3600),
                new StudySessionSubjectSegment(8L, DAY_END.minusSeconds(60), DAY_END.plusSeconds(3600), 3660, 3660));

        Map<Long, SubjectTimeSum> sums = DaySubjectTimes.sum(List.of(session), DAY_START, DAY_END);

        assertThat(sums).containsOnlyKeys(8L);
        assertThat(sums.get(8L).studySec()).isEqualTo(60);
    }

    @Test
    void 창_시작에_걸친_구간은_창_안_조각만_센다() {
        // 20일 04:30~05:30 KST 구간(저장값 3600/3000). 창 안은 05:00~05:30. PHONE 04:40~04:50은 창 밖
        Instant segStart = DAY_START.minusSeconds(1800);
        Instant segEnd = DAY_START.plusSeconds(1800);
        StudySession session = session(
                segStart,
                segEnd,
                List.of(new StatusEvent(EventStatus.PHONE, segStart.plusSeconds(600), segStart.plusSeconds(1200))),
                new StudySessionSubjectSegment(7L, segStart, segEnd, 3600, 3000));

        Map<Long, SubjectTimeSum> sums = DaySubjectTimes.sum(List.of(session), DAY_START, DAY_END);

        assertThat(sums.get(7L).studySec()).isEqualTo(1800);
        assertThat(sums.get(7L).focusSec()).isEqualTo(1800);
    }
}
