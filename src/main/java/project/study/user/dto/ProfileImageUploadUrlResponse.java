package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ProfileImageUploadUrlResponse(
        @Schema(description = "S3 presigned PUT URL — 요청한 크기의 JPEG를 Content-Type: image/jpeg로 PUT한다")
        String uploadUrl,

        @Schema(
                description = "업로드 뒤 PUT /api/users/me/profile-image에 보낼 키",
                example = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String key,

        @Schema(description = "uploadUrl 만료 시각 (발급 5분 뒤)", example = "2026-10-28T10:05:00Z")
        Instant expiresAt) {}
