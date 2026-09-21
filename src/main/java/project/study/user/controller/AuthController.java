package project.study.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.service.AuthService;

// 소셜 로그인(login·link·logout)은 파킹 상태 — 재도입 시 feature/BY-383-auth-contract 브랜치 참고
@Tag(name = "Auth", description = "토큰 재발급 API — 비회원(DEVICE) 토큰 쌍은 POST /api/users가 발급한다 (ADR-0019)")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "토큰 재발급", description = """
                    access 토큰이 만료(401 `UNAUTHORIZED`)됐을 때 refresh 토큰으로 새 쌍을 받는다. \
                    refresh는 **1회용**이라 응답의 새 refresh로 갈아끼워야 하며, 앱 전체에서 동시에 하나만 진행한다 — \
                    같은 refresh를 두 번 쓰면 재사용(탈취 의심)으로 보고 그 유저의 refresh를 전량 폐기한다.

                    401 `INVALID_REFRESH_TOKEN`이면 저장된 토큰을 지우고 `POST /api/users`로 기기를 다시 등록해 복구한다.""")
    @ApiResponse(responseCode = "200", description = "재발급 성공 — 새 access·refresh 쌍. 이전 refresh는 무효")
    @ApiResponse(
            responseCode = "401",
            description = "refresh 토큰이 재사용·만료·미존재 — `code`는 `INVALID_REFRESH_TOKEN`",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\": \"INVALID_REFRESH_TOKEN\", \"message\": \"이미 사용된 refresh 토큰입니다\"}")))
    @ApiResponse(
            responseCode = "400",
            description = "refreshToken 누락 또는 64자 초과(access 토큰을 잘못 넣은 경우)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @SecurityRequirements // access가 만료된 상태에서 부르므로 인증 없이 열려 있다
    // 버전 속성이 없는 것은 의도다 — 어떤 API-Version으로도 매칭된다. access 만료 복구 경로가 버전 불일치로 400이 나면
    // 앱이 통째로 잠기고, 구 앱 대응도 계약 차이도 없어 가를 것이 없다 (ADR-0015 갱신 2026-09-22, ADR-0020 결정 2)
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }
}
