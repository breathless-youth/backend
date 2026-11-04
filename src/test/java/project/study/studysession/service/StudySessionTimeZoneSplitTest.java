package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;
import project.study.user.repository.UserRepository;
import project.study.user.service.UserTimeZones;

/** 세션 시간대의 현지 자정·구간 경계로 나누는지 (ADR-0042). 서울은 예전과 같은 결과여야 한다. */
class StudySessionTimeZoneSplitTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(
                mock(StudySessionRepository.class),
                mock(ActiveStudySessionRepository.class),
                Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC),
                (subjectIds, taskIds) -> SubjectLookup.EMPTY,
                new UserTimeZones(mock(UserRepository.class)));
    }

    private static Instant local(ZoneId zone, int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, zone).toInstant();
    }

    private List<StudySession> build(ZoneId zone, Instant start, Instant end) {
        int sec = (int) Duration.between(start, end).toSeconds();
        return service.validateAndBuildSessions(
                new SessionOwner(1L, zone), start, end, sec, sec, List.of(), SessionAttachments.NONE);
    }

    @Test
    void 현지_자정에서_나누고_statDate는_현지_날짜다() {
        for (ZoneId zone : List.of(SEOUL, NEW_YORK, LONDON)) {
            List<StudySession> sessions = build(zone, local(zone, 7, 23, 23), local(zone, 7, 24, 1));

            assertThat(sessions).as(zone.getId()).hasSize(2);
            assertThat(sessions.get(1).getStartedAt()).isEqualTo(local(zone, 7, 24, 0));
            assertThat(sessions)
                    .extracting(StudySession::getStatDate)
                    .containsExactly(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 24));
            assertThat(sessions).extracting(StudySession::getTimeZone).containsOnly(zone.getId());
        }
    }

    @Test
    void 구간은_현지_0_6_12_18시로_나눈다() {
        for (ZoneId zone : List.of(SEOUL, NEW_YORK, LONDON)) {
            // 현지 05시 ~ 19시: NIGHT 1시간, MORNING 6시간, AFTERNOON 6시간, EVENING 1시간
            List<StudySession> sessions = build(zone, local(zone, 7, 24, 5), local(zone, 7, 24, 19));

            assertThat(sessions.getFirst().getSlots())
                    .as(zone.getId())
                    .extracting(SessionSlot::getSlot, SessionSlot::getSlotDate, SessionSlot::getFocusSec)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(TimeSlot.NIGHT, LocalDate.of(2026, 7, 24), 3600),
                            org.assertj.core.groups.Tuple.tuple(TimeSlot.MORNING, LocalDate.of(2026, 7, 24), 21600),
                            org.assertj.core.groups.Tuple.tuple(TimeSlot.AFTERNOON, LocalDate.of(2026, 7, 24), 21600),
                            org.assertj.core.groups.Tuple.tuple(TimeSlot.EVENING, LocalDate.of(2026, 7, 24), 3600));
        }
    }

    @Test
    void 서머타임이_시작해_23시간인_날은_23시간_조각이_된다() {
        // 뉴욕 2026-03-07 22시 ~ 03-08 22시(EDT) = 23시간, 03-08 하루 전체(23시간)를 덮는다
        Instant start = local(NEW_YORK, 3, 7, 22);
        Instant end = start.plus(Duration.ofHours(23));

        List<StudySession> sessions = build(NEW_YORK, start, end);

        assertThat(sessions)
                .extracting(StudySession::getStatDate)
                .containsExactly(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8));
        assertThat(sessions.get(0).getStudySec()).isEqualTo(2 * 3600);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(21 * 3600);
        assertThat(sessions.get(1).getEndedAt()).isEqualTo(local(NEW_YORK, 3, 8, 22));
    }

    @Test
    void 서머타임이_끝나_25시간인_날도_한_조각이고_자정에서만_나뉜다() {
        // 뉴욕 2026-11-01 00시 ~ 23시(EST) = 24시간, 01시가 두 번이라 아직 같은 날이다
        Instant start = local(NEW_YORK, 11, 1, 0);
        Instant end = start.plus(Duration.ofHours(24));

        List<StudySession> sessions = build(NEW_YORK, start, end);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().getStatDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(end).isEqualTo(local(NEW_YORK, 11, 1, 23));
        // NIGHT은 0~6시가 7시간이다
        assertThat(sessions.getFirst().getSlots())
                .filteredOn(slot -> slot.getSlot() == TimeSlot.NIGHT)
                .extracting(SessionSlot::getFocusSec)
                .containsExactly(7 * 3600);
    }
}
