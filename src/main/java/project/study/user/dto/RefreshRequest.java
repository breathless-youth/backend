package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @Schema(description = "로그인/재발급 응답으로 받은 refresh 토큰", example = "550e8400-e29b-41d4-a716-446655440000") @NotBlank
        String refreshToken) {}
