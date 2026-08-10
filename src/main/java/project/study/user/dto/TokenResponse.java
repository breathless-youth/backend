package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "새 access 토큰(JWT, 30분)") String accessToken,

        @Schema(description = "새 refresh 토큰(UUID, 30일) — 기존 토큰은 무효화됨")
        String refreshToken) {}
