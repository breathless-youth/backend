package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProfileImageUploadUrlRequest(
        @Schema(description = "올릴 JPEG의 바이트 수 — 1 이상 1,048,576(1MB) 이하", example = "123456")
        @NotNull
        @Min(1)
        @Max(MAX_BYTES)
        Long contentLength) {

    public static final long MAX_BYTES = 1_048_576;
}
