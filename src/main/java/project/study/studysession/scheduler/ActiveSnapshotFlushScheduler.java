package project.study.studysession.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.studysession.buffer.ActiveSnapshotBuffer;

/**
 * 코얼레싱 버퍼를 주기적으로 flush한다 (BY-470).
 *
 * <p>버퍼링이 켜져 있을 때만 등록된다(active-session.buffer.enabled, 기본 on). 버퍼를 끄면
 * 서비스가 요청 시 바로 UPSERT하므로 이 스케줄러도 필요 없다. 테스트는 버퍼링을 끄거나
 * {@link ActiveSnapshotBuffer#flush()}를 직접 호출해 타이밍을 통제한다.
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
 * 로그만 남기고 삼킨다 (다른 스케줄러와 동일).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "active-session.buffer.enabled", havingValue = "true", matchIfMissing = true)
public class ActiveSnapshotFlushScheduler {

    private final ActiveSnapshotBuffer buffer;

    @Scheduled(
            initialDelayString = "${active-session.buffer.flush-interval-ms:5000}",
            fixedDelayString = "${active-session.buffer.flush-interval-ms:5000}")
    public void flush() {
        try {
            buffer.flush();
        } catch (Exception e) {
            log.error("스냅샷 버퍼 flush 스케줄러 실패", e);
            Sentry.captureException(e);
        }
    }
}
