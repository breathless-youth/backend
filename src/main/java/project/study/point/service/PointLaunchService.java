package project.study.point.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.point.repository.PointLaunchRepository;
import project.study.user.service.AppStoreVersionSource;
import project.study.user.service.AppVersions;

/**
 * 포인트 기능이 든 앱이 출시됐는지 App Store에서 보고, 출시된 날을 포인트 시작일로 적는다 (BY-1314). 날짜는 서울 기준이다. 조회가
 * 실패하면 아무것도 하지 않고 다음에 다시 본다. 조회 API의 캐시로 출시보다 몇 시간 늦게 볼 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointLaunchService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PointStart start;
    private final PointLaunchRepository repository;
    private final AppStoreVersionSource store;
    private final Clock clock;

    /** 시작일을 새로 적었으면 true. */
    public boolean sync() {
        String version = start.launchVersion();
        if (version.isEmpty() || repository.startedOn(version).isPresent()) {
            return false;
        }
        Optional<String> released;
        try {
            released = store.releasedVersion();
        } catch (RuntimeException e) {
            log.warn("App Store 출시 버전을 읽지 못했습니다: {}", e.toString());
            return false;
        }
        if (released.isEmpty() || !AppVersions.atLeast(released.get(), version)) {
            return false;
        }
        LocalDate today = clock.instant().atZone(SEOUL).toLocalDate();
        boolean started = repository.start(version, today);
        if (started) {
            log.info("포인트를 쌓기 시작합니다: App Store {} 출시, 시작일 {}", released.get(), today);
        }
        return started;
    }
}
