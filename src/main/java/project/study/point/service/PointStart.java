package project.study.point.service;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.point.repository.PointLaunchRepository;

/**
 * 포인트를 쌓기 시작한 날 (BY-1314). 공부·출석·하루 목표·광고 포인트는 이날부터만 준다.
 *
 * <p>{@code app.points.launch.version}(운영: 26.47.0)이 있으면, 그 버전이 App Store에 출시된 것을 서버가 처음 본 날이다
 * ({@link PointLaunchService}). 아직 출시 전이면 시작일이 없어 아무것도 쌓이지 않는다. 설정이 없는 서버(dev·테스트)는
 * {@code app.points.study.starts-on}을 쓴다.
 *
 * <p>시작일은 한 번 정해지면 바뀌지 않으므로 정해진 뒤에는 기억해 둔다. 정해지기 전에는 매번 DB를 본다(키 하나 조회).
 */
@Component
public class PointStart {

    private final StudyPointPolicy policy;
    private final PointLaunchRepository repository;
    private final String launchVersion;

    private volatile LocalDate cached;

    public PointStart(
            StudyPointPolicy policy,
            PointLaunchRepository repository,
            @Value("${app.points.launch.version:}") String launchVersion) {
        this.policy = policy;
        this.repository = repository;
        this.launchVersion = launchVersion.trim();
    }

    /** 이 날짜의 활동에 포인트를 주는지. */
    public boolean counts(LocalDate date) {
        LocalDate startsOn = startsOn();
        return startsOn != null && !date.isBefore(startsOn);
    }

    /** 시작일. 출시 전이면 null. */
    public LocalDate startsOn() {
        if (launchVersion.isEmpty()) {
            return policy.startsOn();
        }
        LocalDate known = cached;
        if (known != null) {
            return known;
        }
        // 정해지기 전에는 매번 DB를 본다. 다른 태스크가 적은 시작일도 바로 보이고, 늦게 끝난 조회가 시작일을 덮지 않는다
        LocalDate found = repository.startedOn(launchVersion).orElse(null);
        if (found != null) {
            cached = found;
        }
        return found;
    }

    /** 포인트를 쌓기 시작했는지. 날짜와 상관없는 보상(시작 미션)이 쓴다. */
    public boolean started() {
        return startsOn() != null;
    }

    public String launchVersion() {
        return launchVersion;
    }
}
