package project.study.user.controller;

// AUTH-DISABLED: 소셜 로그인은 후순위로 미뤄짐 (ADR-0004) — 재도입 시 feature/BY-383-auth-contract 브랜치 참고
//
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.media.Content;
// import io.swagger.v3.oas.annotations.media.ExampleObject;
// import io.swagger.v3.oas.annotations.media.Schema;
// import io.swagger.v3.oas.annotations.responses.ApiResponse;
// import io.swagger.v3.oas.annotations.security.SecurityRequirements;
// import io.swagger.v3.oas.annotations.tags.Tag;
// import jakarta.validation.Valid;
// import lombok.RequiredArgsConstructor;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.MediaType;
// import org.springframework.security.core.annotation.AuthenticationPrincipal;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.ResponseStatus;
// import org.springframework.web.bind.annotation.RestController;
// import project.study.common.ErrorResponse;
// import project.study.user.dto.LinkSocialRequest;
// import project.study.user.dto.LoginRequest;
// import project.study.user.dto.LoginResponse;
// import project.study.user.dto.RefreshRequest;
// import project.study.user.dto.TokenResponse;
// import project.study.user.service.AuthService;
//
// @Tag(name = "Auth", description = "소셜 계정으로 로그인하고, 로그인 상태를 유지·해제하는 API 모음")
// @RestController
// @RequestMapping("/api/auth")
// @RequiredArgsConstructor
// public class AuthController {
//
//     private final AuthService authService;
//
//     @PostMapping("/login")
//     public LoginResponse login(@Valid @RequestBody LoginRequest request) {
//         return authService.login(request);
//     }
//
//     @PostMapping("/refresh")
//     public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
//         return authService.refresh(request);
//     }
//
//     @PostMapping("/logout")
//     @ResponseStatus(HttpStatus.NO_CONTENT)
//     public void logout(@Valid @RequestBody RefreshRequest request) {
//         authService.logout(request.refreshToken());
//     }
//
//     @PostMapping("/link")
//     public LoginResponse linkSocial(
//             @AuthenticationPrincipal Long userId, @Valid @RequestBody LinkSocialRequest request) {
//         return authService.linkSocialAccount(userId, request);
//     }
// }
