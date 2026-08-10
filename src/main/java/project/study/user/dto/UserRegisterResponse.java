package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserRegisterResponse(
        @Schema(description = "발급된 유저 ID — 이후 모든 API 호출에 이 값을 사용한다", example = "1")
        Long userId,

        @Schema(description = "이번 요청으로 유저가 새로 생성됐으면 true, 이미 등록된 기기의 재등록이면 false", example = "true")
        boolean isNew,

        @Schema(description = "API 인증용 access 토큰(JWT, 30분). Authorization: Bearer 헤더에 담아 보낸다")
        String accessToken,

        @Schema(description = "재발급용 opaque refresh 토큰(UUID, 30일, 1회용). 기기에 안전하게 보관")
        String refreshToken) {}
