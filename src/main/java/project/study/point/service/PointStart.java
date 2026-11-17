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
    private final PointTesters testers;
    private final String launchVersion;

    private volatile LocalDate cached;

    public PointStart(
            StudyPointPolicy policy,
            PointLaunchRepository repository,
            PointTesters testers,
            @Value("${app.points.launch.version:}") String launchVersion) {
        this.policy = policy;
        this.repository = repository;
        this.testers = testers;
        this.launchVersion = launchVersion.trim();
    }

    /**
     * 이 사람의 이 날짜 활동에 포인트를 주는지. 스토어 심사 계정과 팀 테스트 계정({@link PointTesters})은 시작일과 상관없이 준다 (BY-1318) — 심사자는 출시 전의 앱으로 시험하므로,
     * 출시일을 기다리면 출석·광고·공부 포인트가 하나도 동작하지 않아 반려될 수 있다.
     */
    public boolean counts(long userId, LocalDate date) {
        if (testers.isTester(userId)) {
            return true;
        }
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

    /** 이 사람에게 포인트를 쌓기 시작했는지. 날짜와 상관없는 보상(시작 미션)이 쓴다. 심사·테스트 계정은 늘 시작한 것으로 본다. */
    public boolean started(long userId) {
        return testers.isTester(userId) || startsOn() != null;
    }

    public String launchVersion() {
        return launchVersion;
    }
}
