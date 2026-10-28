package project.study.push.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.push.service.FirstSessionPushExperiment;

/**
 * 첫 세션 5분 유도 푸시 실험의 발송 틱 (BY-1070) — 10분마다(KST). 실제 사용자에게 보내는 기능이라 값이 없으면 꺼지고, 실험을
 * 시작할 때 환경별 yaml에서 켠다. 예외를 직접 잡아 Sentry로 올린다 — @Scheduled 밖으로 나가면 Spring이 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.push.first-session-experiment.enabled", havingValue = "true")
public class FirstSessionPushScheduler {

    private final FirstSessionPushExperiment experiment;
    private final Clock clock;

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void tick() {
        try {
            experiment.tick(clock.instant());
        } catch (Exception e) {
            log.error("첫 세션 푸시 실험 틱 실패", e);
            Sentry.captureException(e);
        }
    }
}
