package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionPeriodServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    @Test
    void 기록없는_날을_0으로_채우고_총합을_계산한다() {
        LocalDate from = LocalDate.of(2026, 8, 24);
        LocalDate to = LocalDate.of(2026, 8, 26);
        when(studySessionRepository.findDailyStudyStats(1L, from, to, 60))
                .thenReturn(List.of(new DailyStudyStat(LocalDate.of(2026, 8, 25), 1800L, 1500L)));

        StudyPeriodStatsResponse response = service.periodStats(1L, from, to, null, null);

        assertThat(response.dailyFocusSec())
                .containsExactly(
                        new DailyStudyStat(from, 0L, 0L),
                        new DailyStudyStat(LocalDate.of(2026, 8, 25), 1800L, 1500L),
                        new DailyStudyStat(to, 0L, 0L));
        assertThat(response.totalStudySec()).isEqualTo(1800L);
        assertThat(response.totalFocusSec()).isEqualTo(1500L);
        assertThat(response.previousTotalFocusSec()).isNull();
    }

    @Test
    void compare가_있으면_직전_구간_순공합을_채운다() {
        LocalDate from = LocalDate.of(2026, 8, 24);
        LocalDate to = LocalDate.of(2026, 8, 30);
        LocalDate cFrom = LocalDate.of(2026, 8, 17);
        LocalDate cTo = LocalDate.of(2026, 8, 23);
        when(studySessionRepository.findDailyStudyStats(1L, from, to, 60)).thenReturn(List.of());
        when(studySessionRepository.findDailyStudyStats(1L, cFrom, cTo, 60))
                .thenReturn(List.of(new DailyStudyStat(cFrom, 5000L, 4000L), new DailyStudyStat(cTo, 3000L, 2000L)));

        StudyPeriodStatsResponse response = service.periodStats(1L, from, to, cFrom, cTo);

        assertThat(response.previousTotalFocusSec()).isEqualTo(6000L);
        assertThat(response.dailyFocusSec()).hasSize(7);
    }

    @Test
    void from이_to보다_이후면_400() {
        assertThatThrownBy(
                        () -> service.periodStats(1L, LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 24), null, null))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void compare를_한쪽만_지정하면_400() {
        assertThatThrownBy(() -> service.periodStats(
                        1L, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 17), null))
                .isInstanceOf(InvalidSessionException.class);
    }
}
