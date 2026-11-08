package project.study.mission.controller;

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
import project.study.mission.dto.HomeMissionResponse;
import project.study.mission.service.HomeMissionService;

@Tag(name = "Home mission", description = "API-Version 1, 토큰 인증 필요. 시작 날짜 이후 같은 현지 날짜의 확정 측정 순공을 합산한다. 수동 기록은 제외한다.")
@ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content = @Content(examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다\"}")))
@RestController
@RequestMapping(value = "/api/home/mission", version = "1")
@RequiredArgsConstructor
public class HomeMissionController {
    private final HomeMissionService service;

    @Operation(
            summary = "계정별 홈 미션 조회",
            description =
                    "completedSteps는 완료 확인한 단계 수, currentStep은 현재 단계(모두 완료하면 null), eligible은 현재 단계의 조건 충족 여부다. measuredFocusSec은 시작일 이후 하루 중 가장 긴 측정 순공 합계다. startsOn은 인정 시작 날짜다.")
    @ApiResponse(
            responseCode = "200",
            description = "미션 상태",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"version\":\"home-v3\",\"startsOn\":\"2026-11-07\",\"completedSteps\":0,\"currentStep\":1,\"eligible\":true,\"measuredFocusSec\":0}")))
    @GetMapping
    public HomeMissionResponse get(@AuthenticationPrincipal Long userId) {
        return service.get(userId);
    }

    @Operation(summary = "요청한 미션 단계 완료 확인", description = "이미 완료한 단계의 재요청은 현재 상태를 반환한다. 조건 미충족이나 순서 오류는 400이다.")
    @ApiResponse(
            responseCode = "400",
            description = "단계 범위·순서 오류 또는 조건 미충족",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"BAD_REQUEST\",\"message\":\"현재 단계의 완료 조건을 충족하지 않았습니다\"}")))
    @PostMapping("/steps/{step}/complete")
    public HomeMissionResponse complete(@AuthenticationPrincipal Long userId, @PathVariable int step) {
        return service.complete(userId, step);
    }
}
