package project.study.metrics.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.job.MetricsJobRunner;

@ExtendWith(MockitoExtension.class)
class DailyReportJobTest {

    @Mock
    private MetricsJobRunner runner;

    @Mock
    private DailyReportService reportService;

    @Test
    void 스케줄은_지표_전용_스레드에_제출만_한다() {
        new DailyReportJob(runner, reportService).sendDailyReport();

        verify(runner).submit(eq("daily-report"), any(Runnable.class));
    }
}
