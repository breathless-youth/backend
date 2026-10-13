package project.study.metrics.job;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import project.study.metrics.amplitude.AmplitudeIngestService;
import project.study.metrics.report.DailyReportService;

/**
 * 로컬 확인용 — {@code app.report.run-once=true}면 기동 직후 지표 작업을 한 번 돌린다. 값이 없으면 꺼짐이 기본이고(BY-640),
 * 로컬 yaml에서만 켠다.
 */
@Component
@ConditionalOnProperty(name = "app.report.run-once", havingValue = "true")
@RequiredArgsConstructor
public class MetricsRunOnce implements ApplicationRunner {

    private final MetricsJobRunner runner;
    private final AmplitudeIngestService ingestService;
    private final DailyReportService reportService;

    @Override
    public void run(ApplicationArguments args) {
        // 단일 스레드라 적재가 끝난 뒤 리포트가 돈다
        runner.submit("amplitude-ingest(run-once)", ingestService::ingestPending);
        runner.submit("daily-report(run-once)", reportService::send);
    }
}
