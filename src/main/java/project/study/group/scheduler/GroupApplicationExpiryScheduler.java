package project.study.group.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.group.service.GroupApplicationExpiry;

/**
 * 그룹 신청 만료 스케줄러 (BY-1124) — 60초마다 기한 된 신청을 닫는다. 기본은 켜짐이고 {@code app.group.scheduler.enabled=false}로 끌 수
 * 있다(테스트는 끄고 {@link GroupApplicationExpiry}를 직접 부른다 — 백그라운드 만료가 테스트가 만든 신청을 건드리는 경합을 막는다).
 *
 * <p>서버가 여러 대여도 안전하다 — 한 행을 한 작업만 바꾸는 조건부 UPDATE라 푸시가 한 번만 나간다(ShedLock 없음). {@code fixedDelay}라
 * 한 번이 밀려도 몰아서 따라잡지 않는다. 예외는 직접 잡아 Sentry로 올린다 — {@code @Scheduled} 밖으로 나가면 Spring이 로그만 남기고
 * 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.group.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class GroupApplicationExpiryScheduler {

    private final GroupApplicationExpiry expiry;
    private final Clock clock;

    @Scheduled(fixedDelay = 60_000)
    public void expireDueApplications() {
        try {
            int expired = expiry.expireDue(clock.instant());
            if (expired > 0) {
                log.info("그룹 신청 {}건 만료", expired);
            }
        } catch (Exception e) {
            log.error("그룹 신청 만료 작업 실패", e);
            Sentry.captureException(e);
        }
    }
}
