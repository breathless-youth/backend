package project.study.studysession.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.studysession.buffer.ActiveSnapshotBuffer;

/**
 * 코얼레싱 버퍼를 주기적으로 flush한다 (BY-470).
 *
 * <p>스냅샷은 항상 버퍼를 거친다(즉시 UPSERT 경로 없음). 테스트는 flush 주기를 크게 잡거나
 * {@link ActiveSnapshotBuffer#flush()}를 직접 호출해 타이밍을 통제한다.
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
 * 로그만 남기고 삼킨다 (다른 스케줄러와 동일).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveSnapshotFlushScheduler {

    private final ActiveSnapshotBuffer buffer;

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void flush() {
        try {
            buffer.flush();
        } catch (Exception e) {
            log.error("스냅샷 버퍼 flush 스케줄러 실패", e);
            Sentry.captureException(e);
        }
    }
}
