package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 구 앱(v1.2.x)의 방 생성 요청 — 본문 userId로 식별한다. contract 시 삭제 (ADR-0020). */
public record LegacyRoomCreateRequest(
        @Schema(description = "방을 만드는 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @NotNull
        Long userId) {}
