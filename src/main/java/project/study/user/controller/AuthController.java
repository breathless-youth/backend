package project.study.user.controller;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
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
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.ResponseStatus;
// import org.springframework.web.bind.annotation.RestController;
// import project.study.user.dto.ErrorResponse;
// import project.study.user.dto.LoginRequest;
// import project.study.user.dto.LoginResponse;
// import project.study.user.dto.RefreshRequest;
// import project.study.user.dto.TokenResponse;
// import project.study.user.service.AuthService;
//
// @Tag(name = "Auth", description = "구글 계정으로 로그인하고, 로그인 상태를 유지·해제하는 API 모음")
// @RestController
// @RequestMapping("/api/auth")
// @RequiredArgsConstructor
// public class AuthController {
//
//     private final AuthService authService;
//
//     @Operation(summary = "구글 소셜 로그인", description = """
//                     앱에서 "구글로 로그인"을 마치면 구글이 앱에 **ID 토큰**(구글이 서명한 디지털 신분증)을 발급해준다. \
//                     앱이 그 ID 토큰을 이 API로 보내면, 서버는 구글에 위조·만료 여부를 확인한 뒤 \
//                     우리 서비스 전용 토큰 두 개를 발급한다.
//
//                     - **access 토큰** — 이후 모든 API 호출 때 신분 증명으로 쓰는 입장권. 30분 뒤 만료된다.
//                     - **refresh 토큰** — 입장권이 만료됐을 때 새 입장권으로 바꾸기 위한 교환권. 30일 유효, 1회용.
//
//                     처음 로그인한 사용자는 이 시점에 자동으로 회원가입되며, 응답의 `isNewUser`가 `true`로 내려온다. \
//                     앱은 이 값을 보고 온보딩(닉네임 설정) 화면으로 보낼지 결정한다.""")
//     @ApiResponse(responseCode = "200", description = "로그인 성공 — access/refresh 토큰 쌍이 발급된다")
//     @ApiResponse(
//             responseCode = "401",
//             description = "ID 토큰 검증 실패 — 위조됐거나, 만료됐거나, 다른 앱용으로 발급된 토큰인 경우",
//             content =
//                     @Content(
//                             mediaType = MediaType.APPLICATION_JSON_VALUE,
//                             schema = @Schema(implementation = ErrorResponse.class),
//                             examples = {
//                                 @ExampleObject(name = "검증 실패", value = "{\"error\": \"구글 ID 토큰 검증에 실패했습니다\"}"),
//                                 @ExampleObject(
//                                         name = "다른 앱용 토큰",
//                                         value = "{\"error\": \"구글 ID 토큰의 대상(aud)이 일치하지 않습니다\"}")
//                             }))
//     @SecurityRequirements // 인증 불필요 (전역 bearer 자물쇠 오버라이드)
//     @PostMapping("/login")
//     public LoginResponse login(@Valid @RequestBody LoginRequest request) {
//         return authService.login(request);
//     }
//
//     @Operation(summary = "토큰 재발급", description = """
//                     만료된(또는 만료가 임박한) access 토큰을 새것으로 바꾼다. \
//                     앱이 보관 중인 **refresh 토큰(교환권)** 을 보내면 새 access/refresh 토큰 쌍을 돌려준다.
//
//                     교환권은 **1회용**이다 — 한 번 쓰면 새 교환권으로 교체되고, 이전 것은 즉시 무효가 된다. \
//                     이미 사용된 교환권이 다시 들어오면 누군가 토큰을 훔쳐 쓰는 상황으로 간주하고, \
//                     안전을 위해 해당 사용자의 refresh 토큰을 전부 폐기한다(모든 기기에서 재로그인 필요).""")
//     @ApiResponse(responseCode = "200", description = "재발급 성공 — 새 토큰 쌍이 발급되고 기존 refresh 토큰은 무효화된다")
//     @ApiResponse(
//             responseCode = "401",
//             description = "refresh 토큰이 유효하지 않음 — 앱은 저장된 토큰을 지우고 로그인 화면으로 보내야 한다",
//             content =
//                     @Content(
//                             mediaType = MediaType.APPLICATION_JSON_VALUE,
//                             schema = @Schema(implementation = ErrorResponse.class),
//                             examples = {
//                                 @ExampleObject(name = "존재하지 않는 토큰", value = "{\"error\": \"유효하지 않은 refresh
// 토큰입니다\"}"),
//                                 @ExampleObject(name = "만료된 토큰", value = "{\"error\": \"만료된 refresh 토큰입니다\"}"),
//                                 @ExampleObject(
//                                         name = "재사용 감지(탈취 의심)",
//                                         description = "이 경우 해당 유저의 refresh 토큰이 전부 폐기된다",
//                                         value = "{\"error\": \"이미 사용된 refresh 토큰입니다\"}")
//                             }))
//     @SecurityRequirements // 인증 불필요 (access 만료 상태에서 호출되는 API)
//     @PostMapping("/refresh")
//     public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
//         return authService.refresh(request);
//     }
//
//     @Operation(summary = "로그아웃 (기기별)", description = """
//                     지금 이 기기가 보관 중인 refresh 토큰(교환권)을 폐기해 로그인 상태를 끝낸다. \
//                     교환권만 무효화하므로 **다른 기기의 로그인은 그대로 유지된다.**
//
//                     access 토큰(입장권)은 서버가 따로 저장하지 않아 만료 시각(발급 후 30분)까지는 형식상 유효하다. \
//                     따라서 앱은 이 API 호출과 함께 기기에 저장된 토큰 쌍을 반드시 삭제해야 한다.""")
//     @ApiResponse(responseCode = "204", description = "로그아웃 완료 — 응답 본문 없음")
//     @ApiResponse(
//             responseCode = "401",
//             description = "access 토큰 누락 또는 무효 — 응답 본문 없음",
//             content = @Content(schema = @Schema(hidden = true)))
//     @PostMapping("/logout")
//     @ResponseStatus(HttpStatus.NO_CONTENT)
//     public void logout(@Valid @RequestBody RefreshRequest request) {
//         authService.logout(request.refreshToken());
//     }
// }
