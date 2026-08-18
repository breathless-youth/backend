package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @Schema(description = "로그인/재발급 응답으로 받은 refresh 토큰", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotBlank
        // 서버 발급 refresh는 UUID(36자) — 임의 길이 입력이 해시·조회까지 흘러들지 않게 상한을 둔다
        @Size(max = 64, message = "refresh 토큰은 64자 이하여야 합니다")
        String refreshToken) {}
