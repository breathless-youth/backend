package project.study.metrics.amplitude;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.job.MetricsJobRunner;

/**
 * {@code app.report.enabled=true}(prod yaml)일 때만 뜬다 — 리포트와 같은 스위치다. 적재만 따로 켤 일이 없고, 리포트가 이
 * 데이터를 읽는다.
 */
@Component
@ConditionalOnProperty(name = "app.report.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AmplitudeIngestJob {

    private final MetricsJobRunner runner;
    private final AmplitudeIngestService ingestService;

    /**
     * 매일 09:00(KST). 어제 창은 오늘 03:00(KST)부터 준비되므로 10시 리포트 전에 어제까지 채운다.
     *
     * <p>zone을 명시하는 이유: ECS 태스크는 UTC로 뜬다.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void ingest() {
        runner.submit("amplitude-ingest", ingestService::ingestPending);
    }
}
