package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RoomJoinRequest(
        @Schema(description = "초대코드 — 숫자 4자리 문자열 (앞자리 0 유지)", example = "3712") @NotBlank
        String inviteCode) {}
