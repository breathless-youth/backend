package project.study.push.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.push.service.RhythmReminder;

/**
 * 정기 맞춤 알림의 발송 틱 — 10분마다(KST). 실제 사용자에게 보내는 기능이라 값이 없으면 꺼진다.
 * 예외를 직접 잡아 Sentry로 올린다 — @Scheduled 밖으로 나가면 Spring이 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.push.rhythm-reminder.enabled", havingValue = "true")
public class RhythmReminderScheduler {

    private final RhythmReminder reminder;
    private final Clock clock;

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void tick() {
        try {
            reminder.tick(clock.instant());
        } catch (Exception e) {
            log.error("정기 맞춤 알림 틱 실패", e);
            Sentry.captureException(e);
        }
    }
}
