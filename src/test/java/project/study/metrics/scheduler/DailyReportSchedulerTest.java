package project.study.metrics.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.service.DailyReportService;

@ExtendWith(MockitoExtension.class)
class DailyReportSchedulerTest {

    @Mock
    private DailyReportService dailyReportService;

    @InjectMocks
    private DailyReportScheduler scheduler;

    @Test
    void 리포트_서비스에_위임한다() {
        scheduler.sendDailyReport();

        verify(dailyReportService).sendDailyReport();
    }

    @Test
    void 발송이_실패해도_예외를_밖으로_던지지_않는다() {
        // @Scheduled 메서드에서 예외가 나가면 Spring이 로그만 남기고 삼켜 Sentry에 남지 않는다
        doThrow(new RuntimeException("Slack 500")).when(dailyReportService).sendDailyReport();

        assertThatCode(() -> scheduler.sendDailyReport()).doesNotThrowAnyException();
    }
}
