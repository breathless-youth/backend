package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 구 앱(v1.2.x)의 등록 응답 — 토큰 필드가 없다. 강제 업데이트 뒤 contract 시 삭제 (ADR-0020). */
public record LegacyUserRegisterResponse(
        @Schema(description = "발급된 유저 ID — 구 앱은 이후 모든 API 호출에 이 값을 요청에 싣는다", example = "1")
        Long userId,

        @Schema(description = "이번 요청으로 유저가 새로 생성됐으면 true, 이미 등록된 기기의 재등록이면 false", example = "true")
        boolean isNew) {}
