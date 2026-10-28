package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileImageUpdateRequest(
        @Schema(description = "업로드 URL 발급 때 받은 키", example = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String key) {}
