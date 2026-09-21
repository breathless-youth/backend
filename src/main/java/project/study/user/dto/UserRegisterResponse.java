package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserRegisterResponse(
        @Schema(description = "이번 요청으로 유저가 새로 생성됐으면 true, 이미 등록된 기기의 재등록이면 false", example = "true")
        boolean isNew,

        @Schema(
                description =
                        "API 인증용 access 토큰(JWT, 30분). 모든 요청의 Authorization: Bearer 헤더에 담는다. userId가 필요하면 sub 클레임을 읽는다")
        String accessToken,

        @Schema(description = "재발급용 opaque refresh 토큰(UUID, 30일, 1회용). 기기 보안 저장소에만 보관한다")
        String refreshToken) {}
