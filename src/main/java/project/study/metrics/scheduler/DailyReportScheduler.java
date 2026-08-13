package project.study.metrics.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.service.DailyReportService;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private final DailyReportService dailyReportService;

    /**
     * 매일 오전 10시(KST) 일일 지표 리포트를 발송한다.
     *
     * <p>zone을 명시하는 이유: ECS 태스크는 UTC로 뜨므로 zone 없이는 KST 오후 7시에 발화한다.
     *
     * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
     * 로그만 남기고 삼켜서, 자동 리졸버(SentryExceptionResolver)가 잡는 HTTP 요청 경로와 달리
     * Sentry에 아무것도 남지 않는다.
     *
     * <p><b>주의</b>: ECS {@code desired_count}를 2 이상으로 올리면 태스크마다 이 스케줄러가
     * 떠서 리포트가 태스크 수만큼 중복 발송된다. 지금은 1이라 문제없고, 나중에 늘려서 중복이
     * 생기더라도 매일 아침 Slack에 바로 드러나므로 조용히 잘못될 위험은 없다. desired_count를
     * 늘릴 계획이 생기면 그때 분산 락이나 외부 스케줄러(EventBridge)로 옮겨야 한다.
     */
    @Profile("prod")
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void sendDailyReport() {
        try {
            dailyReportService.sendDailyReport();
        } catch (Exception e) {
            log.error("일일 지표 리포트 발송 실패", e);
            Sentry.captureException(e);
        }
    }
}
