package project.study.metrics.amplitude;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

/**
 * 처리 안 된 Amplitude 창을 오래된 것부터 적재한다. 대상은 {@code [오늘 − backfillDays, 어제]} 중 처리 기록이 없고 준비된
 * 창이다 — 첫 배포는 이 규칙으로 과거를 채우고, 실패한 창은 다음 실행이 다시 집는다 (스펙 §7.2).
 */
@Slf4j
@Service
public class AmplitudeIngestService {

    // 네트워크 장애 때 남은 창마다 타임아웃(최대 60초)을 기다리지 않게 이번 실행을 멈춘다
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final AmplitudeExportSource source;
    private final AmplitudeWindowWriter writer;
    private final AmplitudeIngestRepository repository;
    private final Clock clock;
    private final int backfillDays;

    public AmplitudeIngestService(
            AmplitudeExportSource source,
            AmplitudeWindowWriter writer,
            AmplitudeIngestRepository repository,
            Clock clock,
            @Value("${metrics.amplitude.backfill-days:60}") int backfillDays) {
        this.source = source;
        this.writer = writer;
        this.repository = repository;
        this.clock = clock;
        this.backfillDays = backfillDays;
    }

    public IngestResult ingestPending() {
        if (!source.isEnabled()) {
            log.warn("Amplitude 키가 없어 적재를 건너뛴다");
            return new IngestResult(0, 0);
        }
        Instant now = clock.instant();
        int written = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (ExportWindow window : pendingWindows(now)) {
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.warn("Amplitude 적재가 연속 {}번 실패해 남은 창은 다음 실행으로 미룬다", consecutiveFailures);
                break;
            }
            if (ingest(window)) {
                written++;
                consecutiveFailures = 0;
            } else {
                failed++;
                consecutiveFailures++;
            }
        }
        log.info("Amplitude 적재 완료 (적재={}, 실패={})", written, failed);
        return new IngestResult(written, failed);
    }

    /** 창 하나를 받아 쓴다. 이미 다른 실행이 쓴 창(선점 실패)도 성공으로 본다. 실패는 Sentry로 올리고 false. */
    private boolean ingest(ExportWindow window) {
        try {
            writer.write(window, source.fetch(window));
            return true;
        } catch (RuntimeException e) {
            log.error("Amplitude 창 적재 실패 (창={})", window.date(), e);
            Sentry.captureException(e);
            return false;
        }
    }

    private List<ExportWindow> pendingWindows(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ExportWindow.KST);
        LocalDate from = today.minusDays(backfillDays);
        Set<LocalDate> processed = repository.findProcessedDates(from, today.minusDays(1));
        return from.datesUntil(today)
                .filter(date -> !processed.contains(date))
                .map(ExportWindow::new)
                .filter(window -> window.isReady(now))
                .toList();
    }

    /**
     * @param written 이번 실행에서 처리한 창 수(선점 실패로 건너뛴 창 포함)
     * @param failed 실패한 창 수
     */
    public record IngestResult(int written, int failed) {}
}
