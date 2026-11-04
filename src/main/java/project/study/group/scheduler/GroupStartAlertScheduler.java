package project.study.group.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.group.service.GroupStartAlerts;

/**
 * 멤버 공부 시작 알림 스케줄러 (BY-1124) — 10초마다 새로 시작된 공부를 알린다. 첫 체크포인트가 시작 약 30초 뒤라 알림은 시작 후 30~40초에 나간다.
 * 기본은 켜짐이고 {@code app.group.scheduler.enabled=false}로 끌 수 있다(테스트는 끄고 {@link GroupStartAlerts}를 직접 부른다).
 *
 * <p>서버가 여러 대여도 안전하다 — 새 draft는 조건부 UPDATE로 한 태스크만 가져가고, 받는 사람은 "1시간에 한 번" 조건부 upsert로 한 번만 정해진다.
 * {@code fixedDelay}라 한 번이 밀려도 몰아서 따라잡지 않는다. 예외는 직접 잡아 Sentry로 올린다 — {@code @Scheduled} 밖으로 나가면 Spring이
 * 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.group.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class GroupStartAlertScheduler {

    private final GroupStartAlerts startAlerts;
    private final Clock clock;

    @Scheduled(fixedDelay = 10_000)
    public void alertNewlyStartedMembers() {
        try {
            int sent = startAlerts.process(clock.instant());
            if (sent > 0) {
                log.info("멤버 공부 시작 알림 {}건", sent);
            }
        } catch (Exception e) {
            log.error("멤버 공부 시작 알림 작업 실패", e);
            Sentry.captureException(e);
        }
    }
}
