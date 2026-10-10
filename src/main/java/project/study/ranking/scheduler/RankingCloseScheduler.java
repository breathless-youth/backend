package project.study.ranking.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.ranking.close.RankingCloser;

/**
 * 랭킹 마감 스케줄러 (BY-828, ADR-0029) — 매분 5초(KST)에 마감할 판을 확정한다. 00시 마감분은 00:01:05에, 재기동으로 놓친 판은
 * 다음 틱에 따라잡는다. 예외를 직접 잡아 Sentry로 올린다 — @Scheduled 밖으로 나가면 Spring이 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ranking.close.enabled", havingValue = "true", matchIfMissing = true)
public class RankingCloseScheduler {

    private final RankingCloser closer;
    private final Clock clock;

    @Scheduled(cron = "5 * * * * *", zone = "Asia/Seoul")
    public void closeDueBoards() {
        try {
            closer.closeDue(clock.instant());
        } catch (Exception e) {
            log.error("랭킹 마감 스케줄 실패", e);
            Sentry.captureException(e);
        }
    }
}
