package project.study.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.repository.DailyReportLogRepository;
import project.study.metrics.slack.SlackNotifier;
import project.study.studysession.dto.HeavyUser;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    // 고정 현재 시각: 2026-08-09T01:00:00Z = KST 10:00 → KST 오늘 = 08-09, 기준일(어제) = 08-08
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 8);

    @Mock
    private DailyReportLogRepository dailyReportLogRepository;

    @Mock
    private UserService userService;

    @Mock
    private StudySessionMetricsService studySessionMetricsService;

    @Mock
    private SlackNotifier slackNotifier;

    private DailyReportService service;

    @BeforeEach
    void setUp() {
        service = new DailyReportService(
                dailyReportLogRepository, userService, studySessionMetricsService, slackNotifier, CLOCK);
    }

    private void givenEnabledAndClaimed() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(dailyReportLogRepository.claim(TODAY)).thenReturn(1);
    }

    @Test
    void 선점에_성공하면_기준일은_어제이고_지표_네_개를_모아_발송한다() {
        givenEnabledAndClaimed();
        when(userService.countTotal()).thenReturn(53L);
        when(userService.countRegisteredOn(ANCHOR)).thenReturn(4L);
        when(studySessionMetricsService.findHeavyUsers(ANCHOR)).thenReturn(List.of(new HeavyUser(14L, 7L)));
        when(studySessionMetricsService.countQualifyingSessionsOn(ANCHOR)).thenReturn(6L);

        service.sendDailyReport();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).send(message.capture());
        assertThat(message.getValue())
                .contains("2026-08-08")
                .contains("총 가입: 53명")
                .contains("#14(7일)")
                .contains("10분 이상 세션: 6건");
    }

    @Test
    void 이미_발송된_날이면_지표를_조회하지도_발송하지도_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(dailyReportLogRepository.claim(TODAY)).thenReturn(0);

        service.sendDailyReport();

        verifyNoInteractions(userService, studySessionMetricsService);
        verify(slackNotifier, never()).send(any());
    }

    @Test
    void 발송기가_비활성이면_날짜를_선점하지_않는다() {
        // 선점해두면 나중에 URL을 설정하고 재배포해도 그날 리포트가 영영 나가지 않는다
        when(slackNotifier.isEnabled()).thenReturn(false);

        service.sendDailyReport();

        verifyNoInteractions(dailyReportLogRepository, userService, studySessionMetricsService);
        verify(slackNotifier, never()).send(any());
    }
}
