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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.service.AuthService;

@Tag(name = "Auth", description = "소셜 로그인(link)·토큰 재발급·로그아웃 API 모음 — 로그인 진입은 link 하나다 (BY-383)")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "토큰 재발급", description = """
                    만료된(또는 만료가 임박한) access 토큰을 새것으로 바꾼다. \
                    앱이 보관 중인 **refresh 토큰(교환권)** 을 보내면 새 access/refresh 토큰 쌍을 돌려준다.

                    교환권은 **1회용**이다 — 한 번 쓰면 새 교환권으로 교체되고, 이전 것은 즉시 무효가 된다. \
                    이미 사용된 교환권이 다시 들어오면 누군가 토큰을 훔쳐 쓰는 상황으로 간주하고, \
                    안전을 위해 해당 사용자의 refresh 토큰을 전부 폐기한다(모든 기기에서 재로그인 필요).""")
    @ApiResponse(responseCode = "200", description = "재발급 성공 — 새 토큰 쌍이 발급되고 기존 refresh 토큰은 무효화된다")
    @ApiResponse(
            responseCode = "401",
            description = "refresh 토큰이 유효하지 않음 — 앱은 저장된 토큰을 지우고 로그인 화면으로 보내야 한다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "존재하지 않는 토큰", value = "{\"message\": \"유효하지 않은 refresh 토큰입니다\"}"),
                                @ExampleObject(name = "만료된 토큰", value = "{\"message\": \"만료된 refresh 토큰입니다\"}"),
                                @ExampleObject(
                                        name = "재사용 감지(탈취 의심)",
                                        description = "이 경우 해당 유저의 refresh 토큰이 전부 폐기된다",
                                        value = "{\"message\": \"이미 사용된 refresh 토큰입니다\"}")
                            }))
    @SecurityRequirements // 인증 불필요 (access 만료 상태에서 호출되는 API)
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "로그아웃 (기기별)", description = """
                    지금 이 기기가 보관 중인 refresh 토큰(교환권)을 폐기해 로그인 상태를 끝낸다. \
                    교환권만 무효화하므로 **다른 기기의 로그인은 그대로 유지된다.**

                    access 토큰(입장권)은 서버가 따로 저장하지 않아 만료 시각(발급 후 30분)까지는 형식상 유효하다. \
                    따라서 앱은 이 API 호출과 함께 기기에 저장된 토큰 쌍을 반드시 삭제해야 한다.""")
    @ApiResponse(responseCode = "204", description = "로그아웃 완료 — 응답 본문 없음")
    @ApiResponse(
            responseCode = "401",
            description = "access 토큰 누락 또는 무효 — 응답 본문 없음",
            content = @Content(schema = @Schema(hidden = true)))
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @Operation(summary = "기기 유저 소셜 계정 연동", description = """
                    기존 DEVICE(익명) 유저가 소셜 계정으로 로그인한다. 어느 경우든 이 기기의 기록이 소셜 계정으로 이어진다.

                    - 해당 소셜 계정이 처음이면(전환): 익명 유저의 식별자만 소셜로 교체된다 — 기존 데이터 전부 유지, isNewUser true.
                    - 해당 소셜 계정이 이미 있으면(병합): 이 기기 익명 유저의 기록이 기존 계정으로 이관되고 익명 유저는 소멸한다.
                      기존 계정의 세션과 시간이 겹치는 익명 세션은 폐기된다(기존 계정 기록 우선). isNewUser false.

                    병합은 되돌릴 수 없다 — 앱은 진행 전 고지 문구를 노출한다.""")
    @ApiResponse(
            responseCode = "200",
            description = "연동 성공 — 소셜 계정 기반 access/refresh 토큰 쌍이 발급된다. "
                    + "응답 수신 즉시 기존(익명) 토큰 쌍을 폐기하고 새 토큰으로 교체한다 — "
                    + "구 access 토큰은 만료 전까지 형식상 유효하므로 계속 쓰면 안 된다.")
    @ApiResponse(
            responseCode = "401",
            description = "ID 토큰 검증 실패(위조·만료·다른 앱용 aud 불일치) 또는 access 토큰 무효 — " + "앱은 소셜 SDK 로그인부터 재시도",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "검증 실패", value = "{\"message\": \"구글 ID 토큰 검증에 실패했습니다\"}"),
                                @ExampleObject(
                                        name = "다른 앱용 토큰",
                                        value = "{\"message\": \"구글 ID 토큰의 대상(aud)이 일치하지 않습니다\"}")
                            }))
    @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 사용자 — 앱은 저장 토큰 삭제 후 POST /api/users로 재등록",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = "{\"message\": \"존재하지 않는 사용자입니다\"}")))
    @ApiResponse(
            responseCode = "409",
            description = "이미 소셜 계정이 연동된 사용자가 link를 호출함 (비정상 흐름) — 현재 토큰을 유지하고 로그인 화면을 닫는다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/link")
    public LoginResponse linkSocial(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody LinkSocialRequest request) {
        return authService.linkSocialAccount(userId, request);
    }
}
