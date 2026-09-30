package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "API 인증용 access 토큰(JWT, 30분). Authorization: Bearer 헤더에 담아 보낸다")
        String accessToken,

        @Schema(description = "재발급용 opaque refresh 토큰(UUID, 30일, 1회용). 기기에 안전하게 보관")
        String refreshToken,

        @Schema(description = "이번 로그인에서 새로 가입했는지 — true면 앱은 온보딩(닉네임 설정) 화면으로 분기")
        boolean isNewUser) {}
