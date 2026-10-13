package project.study.metrics.report;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.job.MetricsJobRunner;

/**
 * 매일 10:00(KST) 일일 리포트 — {@code app.report.enabled=true}(prod yaml)일 때만 뜬다(BY-640).
 *
 * <p>지표 전용 단일 스레드에 제출만 한다. 09:00 Amplitude 적재가 같은 스레드에 먼저 들어가 있으면 적재가 끝난 뒤 돈다.
 * zone을 명시하는 이유: ECS 태스크는 UTC로 뜬다. ECS 태스크가 2개 이상이면 태스크마다 발송한다(중복은 무해, 스펙 §10).
 */
@Component
@ConditionalOnProperty(name = "app.report.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DailyReportJob {

    private final MetricsJobRunner runner;
    private final DailyReportService reportService;

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendDailyReport() {
        runner.submit("daily-report", reportService::send);
    }
}
