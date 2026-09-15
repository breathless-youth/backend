package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RoomJoinRequest(
        @Schema(description = "입장하는 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @NotNull
        Long userId,

        @Schema(description = "초대코드 — 숫자 4자리 문자열 (앞자리 0 유지)", example = "3712") @NotBlank
        String inviteCode) {}
