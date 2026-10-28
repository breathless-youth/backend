package project.study.acquisition.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import project.study.acquisition.entity.InstallPlatform;

public record AcquisitionRequest(
        @Schema(description = "기기 플랫폼", example = "ANDROID") @NotNull
        InstallPlatform platform,

        @Schema(
                description = "Android Play 설치 정보(Install Referrer) 문자열 원문. 읽지 못했거나 iOS면 없거나 빈 문자열",
                example = "utm_source=google-play&utm_medium=organic")
        @Size(max = 4096)
        String installReferrer,

        @Schema(description = "딥링크로 들어온 광고 라벨(iOS 등). 없으면 null 또는 빈 문자열", example = "ios_v2") @Size(max = 64)
        String deepLinkAd,

        @Schema(description = "앱이 설치 정보를 읽은 시각(오프셋 포함 ISO-8601)", example = "2026-10-29T10:12:00+09:00") @NotNull
        OffsetDateTime capturedAt) {}
