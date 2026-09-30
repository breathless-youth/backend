package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 구 앱(v1.2.x)의 방 입장 요청 — 본문 userId로 식별한다. contract 시 삭제 (ADR-0020). */
public record LegacyRoomJoinRequest(
        @Schema(description = "입장하는 유저 ID", example = "1") @NotNull
        Long userId,

        @Schema(description = "초대코드 — 숫자 4자리 문자열 (앞자리 0 유지)", example = "3712") @NotBlank
        String inviteCode) {

    public RoomJoinRequest toRequest() {
        return new RoomJoinRequest(inviteCode);
    }
}
