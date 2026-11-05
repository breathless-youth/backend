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
        Boolean notificationsEnabled,

        @Schema(
                description =
                        "앱 언어, `ko`·`en`·`ja`·`zh-Hant` 등. 보낸 값 그대로 35자 이하(앞뒤 공백 포함)이고, 저장할 때 앞뒤 공백을 지운다. 없거나 빈 값이면 ko",
                example = "en")
        @Size(max = 35)
        String locale) {}
