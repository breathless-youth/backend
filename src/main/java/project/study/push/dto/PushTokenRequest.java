package project.study.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.push.entity.PushPlatform;

public record PushTokenRequest(
        @Schema(description = "FCM 등록 토큰", example = "dXJ0b2tlbi1leGFtcGxl...") @NotBlank @Size(max = 4096)
        String token,

        @Schema(description = "기기 플랫폼", example = "IOS") @NotNull
        PushPlatform platform,

        @Schema(description = "지금 OS 알림 권한이 켜져 있는지", example = "true") @NotNull
        Boolean notificationsEnabled) {}
