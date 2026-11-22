package project.study.mission.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.mission.dto.HomeMissionResponse;
import project.study.mission.service.HomeMissionRewardService;
import project.study.mission.service.HomeMissionService;
import project.study.point.dto.ClaimResponse;

@Tag(name = "Home mission", description = "API-Version 1, 토큰 인증 필요. 시작 날짜 이후 같은 현지 날짜의 확정 측정 순공을 합산한다. 수동 기록은 제외한다.")
@ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content = @Content(examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다\"}")))
@Slf4j
@RestController
@RequestMapping(value = "/api/home/mission", version = "1")
@RequiredArgsConstructor
public class HomeMissionController {
    private final HomeMissionService service;
    private final HomeMissionRewardService rewards;

    @Operation(
            summary = "계정별 홈 미션 조회",
            description =
                    "completedSteps는 완료 확인한 단계 수, currentStep은 현재 단계(모두 완료하면 null), eligible은 현재 단계의 조건 충족 여부다. measuredFocusSec은 시작일 이후 하루 중 가장 긴 측정 순공 합계다. startsOn은 인정 시작 날짜다. includeAccessory=true인 앱에는 첫 소품 구매 단계까지 반환한다.")
    @ApiResponse(
            responseCode = "200",
            description = "미션 상태",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"version\":\"home-v3\",\"startsOn\":\"2026-11-07\",\"completedSteps\":0,\"currentStep\":1,\"eligible\":true,\"measuredFocusSec\":0,\"rewardedPoints\":0,\"claimablePoints\":0,\"stepPoints\":[50,100]}")))
    @GetMapping
    public HomeMissionResponse get(
            @AuthenticationPrincipal Long userId, @RequestParam(defaultValue = "false") boolean includeAccessory) {
        HomeMissionResponse mission = service.get(userId, includeAccessory);
        return mission.withRewards(
                0, rewards.claimablePoints(userId, mission.completedSteps()), rewards.stepPoints(includeAccessory));
    }

    @Operation(
            summary = "요청한 미션 단계 완료 확인",
            description =
                    "이미 완료한 단계의 재요청은 현재 상태를 반환한다. 조건 미충족이나 순서 오류는 400이다. 로그인한 사용자는 완료한 단계의 포인트(과목 만들기 50P, 공부 5분 100P, 첫 포인트 소품 구매 300P)를 이 요청에서 바로 받고 rewardedPoints에 실린다.")
    @ApiResponse(
            responseCode = "400",
            description = "단계 범위·순서 오류 또는 조건 미충족",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"BAD_REQUEST\",\"message\":\"현재 단계의 완료 조건을 충족하지 않았습니다\"}")))
    @ApiResponse(responseCode = "200", description = "성공")
    @PostMapping("/steps/{step}/complete")
    public HomeMissionResponse complete(
            @AuthenticationPrincipal Long userId,
            @PathVariable int step,
            @RequestParam(defaultValue = "false") boolean includeAccessory) {
        return withGrantedRewards(userId, service.complete(userId, step, includeAccessory), includeAccessory);
    }

    @Operation(
            summary = "안 받은 미션 포인트 받기",
            description =
                    "완료한 단계 가운데 아직 안 받은 포인트를 한 번에 받는다. 포인트가 생기기 전에 미션을 끝낸 사용자가 쓴다. 비회원도 받는다(BY-1349). 받을 것이 없으면 에러 없이 granted=false와 잔액을 준다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @PostMapping("/rewards/claim")
    public ClaimResponse claimRewards(
            @AuthenticationPrincipal Long userId, @RequestParam(defaultValue = "false") boolean includeAccessory) {
        return rewards.claim(userId, includeAccessory);
    }

    // 완료는 따로 저장한다. 지급에 실패하면 미수령액을 응답해 다시 받을 수 있게 한다.
    private HomeMissionResponse withGrantedRewards(long userId, HomeMissionResponse mission, boolean includeAccessory) {
        var stepPoints = rewards.stepPoints(includeAccessory);
        int completed = mission.completedSteps();
        try {
            int rewarded =
                    rewards.claimUpTo(userId, includeAccessory, completed).points();
            return mission.withRewards(rewarded, 0, stepPoints);
        } catch (RuntimeException payoutFailure) {
            log.warn("미션 포인트 지급 실패: userId={}", userId, payoutFailure);
        }
        try {
            return mission.withRewards(0, rewards.claimablePoints(userId, completed), stepPoints);
        } catch (RuntimeException lookupFailure) {
            log.warn("미수령 미션 포인트 조회 실패: userId={}", userId, lookupFailure);
            // 3단계는 미수령액조차 확인하지 못하면 다시 완료를 요청할 수 있게 오류를 반환한다.
            if (includeAccessory && completed == 3) throw lookupFailure;
            return mission.withRewards(0, 0, stepPoints);
        }
    }
}
