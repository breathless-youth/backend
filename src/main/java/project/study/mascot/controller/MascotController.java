package project.study.mascot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.mascot.dto.MascotListResponse;
import project.study.mascot.dto.MascotSelectRequest;
import project.study.mascot.service.MascotService;

@Tag(
        name = "Mascot",
        description =
                "API-Version 1, 토큰 인증 필요. 마스코트는 측정한 누적 순공으로 열린다. 이름과 그림은 앱이 code로 고른다. 해금 기준은 서버 값이라 앱 배포 없이 바뀔 수 있다.")
@ApiResponse(
        responseCode = "403",
        description = "비회원",
        content =
                @Content(
                        examples =
                                @ExampleObject(
                                        value = "{\"code\":\"MEMBER_ONLY\",\"message\":\"로그인한 사용자만 쓸 수 있습니다\"}")))
@RestController
@RequestMapping(value = "/api/mascots", version = "1")
@RequiredArgsConstructor
public class MascotController {

    private final MascotService service;

    @Operation(
            summary = "마스코트 목록",
            description =
                    "전체 마스코트와 해금 기준, 열렸는지, 내 누적 순공, 선택한 마스코트를 준다. newlyUnlocked가 true인 마스코트가 있으면 축하 화면을 띄운 뒤 unlocks/seen을 부른다.")
    @GetMapping
    public MascotListResponse list(@AuthenticationPrincipal Long userId) {
        return service.list(userId);
    }

    @Operation(summary = "선택한 마스코트 바꾸기", description = "바뀐 목록을 돌려준다.")
    @ApiResponse(
            responseCode = "409",
            description = "잠긴 마스코트",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\":\"MASCOT_LOCKED\",\"message\":\"아직 열리지 않은 마스코트입니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "없는 마스코트",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\":\"MASCOT_NOT_FOUND\",\"message\":\"없는 마스코트입니다: X\"}")))
    @PutMapping("/selected")
    public MascotListResponse select(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody MascotSelectRequest request) {
        return service.select(userId, request.code());
    }

    @Operation(
            summary = "새로 열린 마스코트를 봤다고 표시",
            description = "지금 열려 있는 마스코트의 newlyUnlocked를 모두 false로 만든다. 여러 번 불러도 결과가 같다.")
    @PostMapping("/unlocks/seen")
    public MascotListResponse markUnlocksSeen(@AuthenticationPrincipal Long userId) {
        return service.markUnlocksSeen(userId);
    }
}
