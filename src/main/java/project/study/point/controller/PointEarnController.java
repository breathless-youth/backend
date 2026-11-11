package project.study.point.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.point.dto.ClaimResponse;
import project.study.point.dto.EarnOverviewResponse;
import project.study.point.service.DailyRewardService;

@Tag(
        name = "Point earn",
        description = "API-Version 1, 토큰 인증 필요. 포인트 받기 탭과 보상 받기. 오늘은 사용자 시간대의 자정 기준이다. "
                + "같은 보상은 한 번만 지급되고, 다시 요청하면 granted=false와 지금 잔액을 준다(에러가 아니다).")
@ApiResponse(
        responseCode = "403",
        description = "비회원",
        content =
                @Content(
                        examples =
                                @ExampleObject(
                                        value = "{\"code\":\"MEMBER_ONLY\",\"message\":\"로그인한 사용자만 쓸 수 있습니다\"}")))
@RestController
@RequestMapping(value = "/api/points", version = "1")
@RequiredArgsConstructor
public class PointEarnController {

    private final DailyRewardService service;

    @Operation(summary = "오늘의 무료 포인트", description = "포인트 받기 탭을 한 번에 그리는 값. 공부·출석·하루 목표의 오늘 상태와 아직 안 받은 메달 목록.")
    @GetMapping("/earn")
    public EarnOverviewResponse overview(@AuthenticationPrincipal Long userId) {
        return service.overview(userId);
    }

    @Operation(summary = "출석 포인트 받기", description = "하루 한 번.")
    @PostMapping("/rewards/attendance/claim")
    public ClaimResponse claimAttendance(@AuthenticationPrincipal Long userId) {
        return service.claimAttendance(userId);
    }

    @Operation(summary = "하루 목표 포인트 받기", description = "오늘 측정한 순공이 오늘의 목표 이상이고 목표가 최소 목표 이상일 때 하루 한 번.")
    @ApiResponse(
            responseCode = "409",
            description = "목표를 아직 못 채움",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"REWARD_NOT_AVAILABLE\",\"message\":\"아직 하루 목표를 채우지 않았습니다\"}")))
    @PostMapping("/rewards/daily-goal/claim")
    public ClaimResponse claimDailyGoal(@AuthenticationPrincipal Long userId) {
        return service.claimDailyGoal(userId);
    }

    @Operation(summary = "메달 포인트 받기", description = "오늘의 무료 포인트 응답의 medals[].recordId를 넣는다. 메달 하나에 한 번.")
    @ApiResponse(
            responseCode = "404",
            description = "내 메달이 아니거나 없는 메달",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\":\"MEDAL_NOT_FOUND\",\"message\":\"받을 수 있는 메달이 아닙니다\"}")))
    @PostMapping("/rewards/medals/{recordId}/claim")
    public ClaimResponse claimMedal(@AuthenticationPrincipal Long userId, @PathVariable long recordId) {
        return service.claimMedal(userId, recordId);
    }
}
