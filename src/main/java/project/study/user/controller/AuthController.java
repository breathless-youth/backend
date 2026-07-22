package project.study.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.user.dto.LoginRequest;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.service.AuthService;

@Tag(name = "Auth", description = "구글 소셜 로그인과 토큰 관리")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "구글 소셜 로그인",
            description = "앱이 Google Sign-In SDK로 받은 ID 토큰을 서버가 구글에 검증(aud 확인)한 뒤 "
                    + "자체 토큰 쌍을 발급한다. 미가입 유저는 이 시점에 자동 가입되며 isNewUser=true로 내려간다.")
    @ApiResponse(responseCode = "401", description = "ID 토큰 검증 실패 — 위조·만료·aud 불일치. 응답: {\"error\": \"사유\"}")
    @SecurityRequirements // 인증 불필요 (전역 bearer 자물쇠 오버라이드)
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(
            summary = "토큰 재발급",
            description = "refresh 토큰을 새 토큰 쌍으로 교환한다(회전 — 기존 refresh 토큰은 1회용으로 소모됨). "
                    + "이미 사용된 refresh 토큰이 다시 오면 탈취로 간주해 해당 유저의 refresh 토큰을 전부 폐기한다.")
    @ApiResponse(responseCode = "401", description = "유효하지 않거나 만료·재사용된 refresh 토큰. 응답: {\"error\": \"사유\"}")
    @SecurityRequirements // 인증 불필요 (access 만료 상태에서 호출되는 API)
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(
            summary = "로그아웃 (기기별)",
            description = "전달된 refresh 토큰만 폐기한다 — 다른 기기의 로그인은 유지된다. "
                    + "access 토큰은 서버가 저장하지 않아 만료(30분)까지 유효하므로, 앱은 저장된 토큰 쌍을 함께 삭제해야 한다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 완료")
    @ApiResponse(responseCode = "401", description = "access 토큰 누락 또는 무효")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
