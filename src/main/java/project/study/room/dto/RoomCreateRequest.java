package project.study.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record RoomCreateRequest(
        @Schema(description = "방을 만드는 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @NotNull
        Long userId) {}
