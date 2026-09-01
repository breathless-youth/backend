package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record RoomCreateResponse(
        @Schema(description = "생성된 방 ID", example = "42") Long roomId,

        @Schema(description = "초대코드 — 숫자 4자리 (앞자리 0 가능), 활성 방 사이에서 유일", example = "3712")
        String inviteCode,

        @Schema(description = "빈 방 자동 소멸까지 남은 시간(초) — 생성 시점 기준 600 고정", example = "600")
        int emptyTtlSeconds) {}
