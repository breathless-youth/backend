package project.study.point.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매시 정각(5분)에 포인트 기능이 든 앱이 출시됐는지 본다 (BY-1314). {@code app.points.launch.version}이 있을 때만 뜬다. */
@Component
@ConditionalOnProperty(name = "app.points.launch.version")
@RequiredArgsConstructor
public class PointLaunchJob {

    private final PointLaunchService service;

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    public void sync() {
        service.sync();
    }
}
