package project.study.mission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record HomeMissionResponse(
        String version,
        LocalDate startsOn,
        int completedSteps,
        Integer currentStep,
        boolean eligible,
        long measuredFocusSec,

        @Schema(description = "이번 요청으로 받은 미션 포인트. 조회이거나 받은 것이 없으면 0", example = "50")
        int rewardedPoints,

        @Schema(description = "완료했지만 아직 안 받은 미션 포인트. 0보다 크면 rewards/claim으로 받는다. 비회원은 늘 0", example = "0")
        int claimablePoints) {

    public HomeMissionResponse withRewards(int rewarded, int claimable) {
        return new HomeMissionResponse(
                version, startsOn, completedSteps, currentStep, eligible, measuredFocusSec, rewarded, claimable);
    }
}
