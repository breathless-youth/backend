package project.study.metrics.report;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.metrics.report.fact.ExperimentData;
import project.study.metrics.report.render.RenderedReport;
import project.study.metrics.report.render.ReportRenderer;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackNotificationException;
import project.study.metrics.slack.SlackNotifier;
import project.study.push.service.FirstSessionPushExperiment;

/**
 * 일일 지표 리포트 v2를 계산해 Slack 본 메시지 + 스레드 7개로 보낸다 (BY-904, 스펙 §9·§10, 실험 스레드는 BY-1070).
 *
 * <p>하루 1회 발송을 DB로 보장하지 않는다 — 날짜를 선점한 뒤 발송이 실패하면 그날 리포트가 영구히 유실되고 이력이 "보냈다"고 거짓
 * 상태를 남긴다. 중복 발송(태스크 2개)은 시끄럽지만 무해하고, 원본 데이터가 남아 리포트는 언제든 다시 계산할 수 있다(BY-461 결정 유지).
 */
@Slf4j
@Service
public class DailyReportService {

    private final ReportDataLoader loader;
    private final FirstSessionPushExperiment firstSessionPushExperiment;
    private final SlackNotifier slackNotifier;
    private final Clock clock;
    private final long threadDelayMs;

    public DailyReportService(
            ReportDataLoader loader,
            FirstSessionPushExperiment firstSessionPushExperiment,
            SlackNotifier slackNotifier,
            Clock clock,
            @Value("${metrics.slack.thread-delay-ms:1000}") long threadDelayMs) {
        this.loader = loader;
        this.firstSessionPushExperiment = firstSessionPushExperiment;
        this.slackNotifier = slackNotifier;
        this.clock = clock;
        this.threadDelayMs = threadDelayMs;
    }

    public void send() {
        if (!slackNotifier.isEnabled()) {
            log.warn("Slack 봇 설정이 없어 일일 리포트를 건너뛴다");
            return;
        }
        // 발송 시각이 10시라 오늘은 부분 집계다. 완결된 하루인 어제를 기준일로 쓴다
        LocalDate anchor = LocalDate.ofInstant(clock.instant(), Kst.ZONE).minusDays(1);
        RenderedReport rendered = ReportRenderer.render(DailyReport.analyze(loader.load(anchor, experimentFacts())));
        String threadTs = slackNotifier.post(rendered.summary());
        int sent = 0;
        for (SlackMessage thread : rendered.threads()) {
            pause();
            sent += reply(threadTs, thread) ? 1 : 0;
        }
        log.info(
                "일일 지표 리포트를 발송했다 (기준일={}, 스레드 {}/{})",
                anchor,
                sent,
                rendered.threads().size());
    }

    // 실험 섹션 하나 때문에 리포트 전체가 막히지 않게 한다 — null이면 그 스레드만 "계산 실패"가 된다
    private ExperimentData experimentFacts() {
        try {
            return firstSessionPushExperiment.findFacts();
        } catch (RuntimeException e) {
            log.error("첫 세션 실험 사실을 읽지 못했다 — 실험 스레드만 계산 실패로 보낸다", e);
            Sentry.captureException(e);
            return null;
        }
    }

    // 스레드 하나가 실패해도 나머지는 보낸다 — 재시도(@Retryable)까지 실패한 댓글만 건너뛴다
    private boolean reply(String threadTs, SlackMessage thread) {
        try {
            slackNotifier.reply(threadTs, thread);
            return true;
        } catch (SlackNotificationException e) {
            log.error("일일 리포트 스레드 댓글 발송 실패 — 건너뛴다", e);
            Sentry.captureException(e);
            return false;
        }
    }

    // Slack 채널당 초당 1건 권장 한도에 맞춘 간격 (테스트는 0)
    private void pause() {
        if (threadDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(threadDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("일일 리포트 발송 중 중단됐다", e);
        }
    }
}
