package project.study.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.user.dto.ReviewLoginRequest;
import project.study.user.dto.ReviewLoginStatusResponse;
import project.study.user.dto.TokenResponse;
import project.study.user.service.AuthService;
import project.study.user.service.ReviewLoginService;

@Tag(name = "Auth")
@RestController
@RequestMapping(value = "/api/auth/review-login", version = "1")
@RequiredArgsConstructor
public class ReviewLoginController {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_CLIENT_LENGTH = 64;

    private final ReviewLoginService service;

    @Operation(summary = "심사용 로그인이 켜져 있는지", description = """
                    토큰 없이 부른다. 스토어 심사자가 쓰는 아이디·비밀번호 로그인의 입구를 그릴지 정한다.                     서버에 적어 둔 출시 버전보다 `appVersion`이 높을 때만 `enabled`가 true다. 출시 버전은 배포 없이 바뀐다.""")
    @ApiResponse(responseCode = "200", description = "성공")
    @SecurityRequirements
    @GetMapping("/status")
    public ReviewLoginStatusResponse status(
            @Parameter(description = "앱 버전", example = "26.47.0") @RequestParam String appVersion) {
        return new ReviewLoginStatusResponse(service.enabled(appVersion));
    }

    @Operation(summary = "심사용 로그인", description = """
                    토큰 없이 부른다. 심사 계정 하나만 아이디·비밀번호로 로그인하고 토큰 쌍을 받는다. 받은 뒤는 다른 로그인과 같다                     (`POST /api/auth/refresh`로 재발급). 심사 계정을 삭제한 뒤 다시 로그인하면 계정을 새로 만들어 처음 상태로 채운다.

                    꺼져 있으면 403 `REVIEW_LOGIN_DISABLED`, 아이디나 비밀번호가 틀리면 400 `INVALID_REVIEW_CREDENTIALS`,                     같은 IP에서 10분에 5번 틀리면 429 `TOO_MANY_REQUESTS`다. 401은 쓰지 않는다(앱이 401을 토큰 재발급으로 받는다).""")
    @ApiResponse(responseCode = "200", description = "성공 — 심사 계정의 access·refresh 쌍")
    @ApiResponse(
            responseCode = "400",
            description = "아이디 또는 비밀번호가 틀림 — `code`는 `INVALID_REVIEW_CREDENTIALS`",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "꺼져 있음",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"REVIEW_LOGIN_DISABLED\",\"message\":\"심사용 로그인을 쓸 수 없습니다\"}")))
    @ApiResponse(
            responseCode = "429",
            description = "시도가 너무 많음 — `code`는 `TOO_MANY_REQUESTS`",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @SecurityRequirements
    @PostMapping
    public TokenResponse login(@Valid @RequestBody ReviewLoginRequest request, HttpServletRequest http) {
        AuthService.TokenPair tokens = service.login(request, clientOf(http));
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 시도 제한의 기준이 되는 IP. 운영은 ALB 뒤라 직접 연결은 늘 ALB다. ALB는 자기가 본 주소를 X-Forwarded-For의 맨 끝에 붙이므로 맨 끝
     * 값을 쓴다 — 앞쪽 값은 요청한 쪽이 꾸밀 수 있다.
     */
    private static String clientOf(HttpServletRequest http) {
        String forwarded = http.getHeader(FORWARDED_FOR);
        String client = http.getRemoteAddr();
        if (forwarded != null && !forwarded.isBlank()) {
            client = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
        }
        return client.length() > MAX_CLIENT_LENGTH ? client.substring(0, MAX_CLIENT_LENGTH) : client;
    }
}
