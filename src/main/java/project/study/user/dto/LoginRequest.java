package project.study.user.dto;

// AUTH-DISABLED: 소셜 로그인은 후순위로 미뤄짐 — 재도입 시 feature/BY-383-auth-contract 브랜치 참고
//
// import io.swagger.v3.oas.annotations.media.Schema;
// import jakarta.validation.constraints.NotBlank;
// import jakarta.validation.constraints.NotNull;
// import project.study.user.entity.Provider;
//
// public record LoginRequest(
//         @Schema(description = "소셜 로그인 프로바이더", example = "GOOGLE") @NotNull
//         Provider provider,
//
//         @Schema(description = "소셜 SDK로 받은 ID 토큰", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6...") @NotBlank
//         String idToken) {}
