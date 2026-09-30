package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import project.study.user.entity.Provider;

public record LoginRequest(
        @Schema(description = "소셜 로그인 프로바이더", example = "GOOGLE") @NotNull
        Provider provider,

        @Schema(description = "소셜 SDK로 받은 ID 토큰", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6...") @NotBlank
        String idToken) {}
