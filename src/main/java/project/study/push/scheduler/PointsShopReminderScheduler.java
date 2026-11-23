package project.study.push.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.push.service.PointsShopReminder;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.push.points-shop-reminder.enabled", havingValue = "true")
public class PointsShopReminderScheduler {

    private final PointsShopReminder reminder;
    private final Clock clock;

    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Seoul")
    public void tick() {
        try {
            reminder.run(clock.instant());
        } catch (RuntimeException e) {
            log.error("포인트샵 리마인드 스케줄 실행 실패", e);
            Sentry.captureException(e);
        }
    }
}
