package project.study.mission.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.point.dto.ClaimResponse;
import project.study.point.dto.PointReason;
import project.study.point.service.PointService;
import project.study.point.service.PointStart;

/**
 * 시작 미션의 단계를 끝내면 포인트를 준다 (BY-1291). 완료한 단계 가운데 아직 안 받은 것을 모두 주므로, 단계 완료 직후의 자동 지급과 이미
 * 미션을 끝낸 사용자의 받기 요청이 같은 길을 쓴다. 참조 키가 "미션 버전:단계"라 단계마다 한 번만 들어간다. 비회원은 포인트를 쓰지 못해 받지
 * 못하고, 로그인한 뒤 받기 요청으로 받는다.
 */
@Service
public class HomeMissionRewardService {

    private final HomeMissionService missions;
    private final PointService points;
    private final PointStart pointStart;
    private final int[] stepPoints;

    public HomeMissionRewardService(
            HomeMissionService missions,
            PointService points,
            PointStart pointStart,
            @Value("${app.points.mission.subject-points:50}") int subjectPoints,
            @Value("${app.points.mission.focus-points:100}") int focusPoints,
            @Value("${app.points.mission.accessory-points:300}") int accessoryPoints) {
        this.missions = missions;
        this.points = points;
        this.pointStart = pointStart;
        this.stepPoints = new int[] {subjectPoints, focusPoints, accessoryPoints};
    }

    /** 완료한 단계 가운데 아직 안 받은 포인트를 모두 준다. 받을 것이 없으면 지급 없이 잔액만 준다. */
    @Transactional
    public ClaimResponse claim(Long userId, boolean includeAccessory) {
        return claimUpTo(userId, includeAccessory, HomeMissionService.stepCount(includeAccessory));
    }

    /** 완료 응답을 보내는 단계까지만 지급해 동시 요청으로 끝낸 다음 단계 보상이 섞이지 않게 한다. */
    @Transactional
    public ClaimResponse claimUpTo(Long userId, boolean includeAccessory, int confirmedSteps) {
        if (!pointStart.started(userId)) {
            // 포인트 기능이 든 앱이 나오기 전에는 주지 않는다. 끝낸 단계는 남아 있어 나온 뒤에 받는다 (BY-1314)
            return new ClaimResponse(false, 0, points.balanceOf(userId));
        }
        points.lock(userId);
        int completed = Math.min(confirmedSteps, missions.completedSteps(userId, includeAccessory));
        int granted = 0;
        for (int step = 1; step <= completed; step++) {
            int amount = stepPoints[step - 1];
            String label = HomeMissionService.VERSION + "/" + step;
            if (amount > 0 && points.earn(userId, PointReason.MISSION, refKey(step), amount, label, null)) {
                granted += amount;
            }
        }
        return new ClaimResponse(granted > 0, granted, points.balanceOf(userId));
    }

    /** 완료했지만 아직 안 받은 포인트. 비회원은 0. */
    @Transactional(readOnly = true)
    public int claimablePoints(long userId, int completedSteps) {
        if (completedSteps == 0 || !pointStart.started(userId)) {
            return 0;
        }
        int claimable = 0;
        for (int step = 1; step <= completedSteps; step++) {
            if (!points.granted(userId, PointReason.MISSION, refKey(step))) {
                claimable += stepPoints[step - 1];
            }
        }
        return claimable;
    }

    /** 단계별 포인트. 1단계부터 차례대로. */
    public List<Integer> stepPoints(boolean includeAccessory) {
        return Arrays.stream(stepPoints, 0, HomeMissionService.stepCount(includeAccessory))
                .boxed()
                .toList();
    }

    private static String refKey(int step) {
        return HomeMissionService.VERSION + ":" + step;
    }
}
