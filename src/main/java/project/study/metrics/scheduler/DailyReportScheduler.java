package project.study.metrics.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendDailyReport() {
        try {
            dailyReportService.sendDailyReport();
        } catch (Exception e) {
            log.error("일일 지표 리포트 발송 실패", e);
            Sentry.captureException(e);
        }
    }
}
