package project.study.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매시 정각에 심사용 로그인의 출시 버전을 App Store에 맞춘다 (BY-1312). {@code app.review-login.release-sync.enabled=true}일 때만 뜬다.
 * 태스크가 여럿이어도 올리기만 하므로 겹쳐도 무해하다.
 */
@Component
@ConditionalOnProperty(name = "app.review-login.release-sync.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReviewReleaseSyncJob {

    private final ReviewReleaseSyncService service;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void sync() {
        service.sync();
    }
}
