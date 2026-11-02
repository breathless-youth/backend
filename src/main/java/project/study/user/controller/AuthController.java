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
import project.study.common.exception.ErrorResponse;
import project.study.user.dto.LinkResponse;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.service.AuthService;

@Tag(name = "Auth", description = "소셜 로그인·토큰 재발급 API — 비회원(DEVICE) 토큰 쌍은 POST /api/users가 발급한다 (ADR-0019)")
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
    // 구 앱(v1.2.x)에 없던 새 경로라 기본버전 1이다 — 깨지는 변경이 생기면 그때 2를 병행한다 (ADR-0015 갱신 2026-09-22)
    @PostMapping(value = "/refresh", version = "1")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "소셜 로그인", description = """
                    비회원 access 토큰을 들고 소셜 SDK의 ID 토큰으로 로그인한다. 응답의 토큰 쌍으로 갈아끼운다 — 이 기기의 이전 refresh는 무효다.

                    - 처음 쓰는 소셜 계정이면 **전환**: 지금 비회원이 그대로 그 소셜 계정이 된다(userId·기록 유지). `isNewUser: true`
                    - 이미 있는 소셜 계정이면 **병합**: 이 기기의 기록을 그 계정으로 옮기고 지금 비회원은 사라진다. \
                    부딪히는 기록은 기존 계정 것이 남는다. 응답 access의 `sub`가 기존 계정 userId로 바뀐다. `isNewUser: false`
                    - 병합이면, 방 안에 있던 경우 방에서 나간 뒤 처리한다(다른 사람에게 `MEMBER_LEFT`). 전환·409·404는 방을 건드리지 않는다.

                    **nonce**: 앱이 매번 원문을 만들어 그 SHA-256 **소문자 hex**를 SDK에 넘기고, 원문은 `nonce`에 싣는다. \
                    ID 토큰에 nonce가 들어 있으면 원문의 해시와 같아야 하고, **애플은 필수**다. \
                    SDK에 nonce를 넘기지 않은 구글·카카오 토큰은 `nonce` 없이 보낸다.""")
    @ApiResponse(responseCode = "200", description = "전환(`isNewUser: true`) 또는 병합(`false`) — 새 access·refresh 쌍")
    @ApiResponse(
            responseCode = "400",
            description = """
                    `INVALID_SOCIAL_TOKEN`: ID 토큰 검증 실패(위조·만료·다른 앱용), nonce 불일치·누락, \
                    지원하지 않는 프로바이더 값 `DEVICE` — 소셜 로그인부터 다시. \
                    `VALIDATION_FAILED`: 요청 형식(provider 누락, idToken 공백·4096자 초과, nonce 128자 초과). \
                    `BAD_REQUEST`: 본문을 읽을 수 없음 — `GOOGLE`·`KAKAO`·`APPLE`·`DEVICE` 밖의 provider 문자열도 여기다""",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "소셜 토큰",
                                        value =
                                                "{\"code\": \"INVALID_SOCIAL_TOKEN\", \"message\": \"nonce가 일치하지 않습니다\"}"),
                                @ExampleObject(
                                        name = "요청 형식",
                                        value =
                                                "{\"code\": \"VALIDATION_FAILED\", \"message\": \"idToken: 공백일 수 없습니다\"}"),
                                @ExampleObject(
                                        name = "모르는 프로바이더",
                                        value = "{\"code\": \"BAD_REQUEST\", \"message\": \"요청 본문을 읽을 수 없습니다\"}")
                            }))
    @ApiResponse(
            responseCode = "401",
            description = "우리 access 토큰이 없거나 무효 — 기존 refresh 흐름",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(value = "{\"code\": \"UNAUTHORIZED\", \"message\": \"인증이 필요합니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "토큰의 사용자가 없다(지워진 비회원) — 토큰을 지우고 POST /api/users로 다시 등록",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\": \"USER_NOT_FOUND\", \"message\": \"존재하지 않는 사용자입니다\"}")))
    @ApiResponse(
            responseCode = "409",
            description = "이미 소셜 계정인 사용자 — 이미 로그인 상태이므로 로그인 화면을 닫는다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\": \"ALREADY_LINKED\", \"message\": \"이미 소셜 계정으로 로그인한 사용자입니다\"}")))
    @PostMapping(value = "/link", version = "1")
    public LinkResponse link(@AuthenticationPrincipal Long userId, @Valid @RequestBody LinkSocialRequest request) {
        return authService.link(userId, request);
    }

    @Operation(summary = "로그아웃", description = """
                    이 기기의 refresh 토큰만 폐기한다. 같은 계정의 다른 기기는 로그인 상태로 남는다. \
                    없는 토큰이거나 남의 토큰이어도 204다 — 앱은 응답과 상관없이 저장된 토큰을 지우고 `POST /api/users`로 새 비회원이 된다.

                    이미 회전돼 쓸 수 없게 된 **내** refresh를 보내면 탈취 의심으로 보고 이 계정의 refresh를 전량 폐기한다.""")
    @ApiResponse(responseCode = "204", description = "처리됨 — 본문 없음")
    @ApiResponse(
            responseCode = "400",
            description = "refreshToken 누락 또는 64자 초과",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "우리 access 토큰이 없거나 무효 — 기존 refresh 흐름",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(value = "{\"code\": \"UNAUTHORIZED\", \"message\": \"인증이 필요합니다\"}")))
    @PostMapping(value = "/logout", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Long userId, @Valid @RequestBody RefreshRequest request) {
        authService.logout(userId, request.refreshToken());
    }
}
