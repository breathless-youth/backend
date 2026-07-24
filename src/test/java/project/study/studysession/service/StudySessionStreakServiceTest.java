package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionStreakServiceTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00) → KST 오늘 = 2026-07-24
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

    @Mock
    private StudySessionRepository studySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, CLOCK);
    }

    @Test
    void 스트릭은_오늘부터_거꾸로_이어진_연속_공부일이다() {
        when(studySessionRepository.findDistinctStatDates(1L))
                .thenReturn(List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(4)));

        StudySessionStreakResponse response = service.streak(1L);

        assertThat(response.streak()).isEqualTo(3);
        assertThat(response.maxStreak()).isEqualTo(3);
    }

    @Test
    void 오늘_기록이_없어도_어제까지_이어진_스트릭은_유지된다() {
        when(studySessionRepository.findDistinctStatDates(1L))
                .thenReturn(List.of(TODAY.minusDays(1), TODAY.minusDays(2)));

        assertThat(service.streak(1L).streak()).isEqualTo(2);
    }

    @Test
    void 어제도_오늘도_기록이_없으면_스트릭은_0이다() {
        when(studySessionRepository.findDistinctStatDates(1L))
                .thenReturn(List.of(TODAY.minusDays(2), TODAY.minusDays(3)));

        StudySessionStreakResponse response = service.streak(1L);

        assertThat(response.streak()).isEqualTo(0);
        assertThat(response.maxStreak()).isEqualTo(2);
    }

    @Test
    void 최장_스트릭은_끊긴_구간들_중_가장_긴_연속_구간이다() {
        // 오늘~어제(2일) + 4~7일 전(4일)
        when(studySessionRepository.findDistinctStatDates(1L))
                .thenReturn(List.of(
                        TODAY,
                        TODAY.minusDays(1),
                        TODAY.minusDays(4),
                        TODAY.minusDays(5),
                        TODAY.minusDays(6),
                        TODAY.minusDays(7)));

        StudySessionStreakResponse response = service.streak(1L);

        assertThat(response.streak()).isEqualTo(2);
        assertThat(response.maxStreak()).isEqualTo(4);
    }

    @Test
    void 시계_오차로_생긴_내일_날짜는_스트릭에_세지_않는다() {
        // 미래 종료 허용 오차(5분) 탓에 자정 직후 조각이 내일 날짜로 저장될 수 있다
        when(studySessionRepository.findDistinctStatDates(1L))
                .thenReturn(List.of(TODAY.plusDays(1), TODAY, TODAY.minusDays(1)));

        StudySessionStreakResponse response = service.streak(1L);

        assertThat(response.streak()).isEqualTo(2);
        assertThat(response.maxStreak()).isEqualTo(2);
    }

    @Test
    void 기록이_없으면_스트릭과_최장_스트릭_모두_0이다() {
        when(studySessionRepository.findDistinctStatDates(1L)).thenReturn(List.of());

        StudySessionStreakResponse response = service.streak(1L);

        assertThat(response.streak()).isEqualTo(0);
        assertThat(response.maxStreak()).isEqualTo(0);
    }
}
