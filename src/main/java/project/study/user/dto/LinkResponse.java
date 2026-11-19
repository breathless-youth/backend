package project.study.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record LinkResponse(
        @Schema(description = "새 access 토큰(JWT, 30분). userId가 필요하면 sub 클레임을 읽는다 — 병합이면 기존 소셜 계정의 userId다")
        String accessToken,

        // 쿠키 모드(브라우저)에서는 쿠키로만 주므로 필드째 뺀다
        @Schema(description = "새 refresh 토큰(UUID, 30일, 1회용). 이 기기의 이전 refresh는 무효다. 쿠키 모드면 없다")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken,

        @Schema(description = "처음 쓰는 소셜 계정이라 이 비회원이 그대로 전환됐으면 true, 기존 소셜 계정에 합쳐졌으면 false", example = "true")
        boolean isNewUser) {}
