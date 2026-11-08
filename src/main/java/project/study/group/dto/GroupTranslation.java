package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "선택 언어 번역 — 각 필드가 null이면 기존 원문 필드를 표시한다")
public record GroupTranslation(
        @Schema(
                allowableValues = {"ko", "en", "ja", "zh-TW"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String language,

        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "번역 이름 — 없으면 null, 원문 name을 표시")
        String name,

        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "번역 소개 — 목록·미번역·소개 없음이면 null, 원문 description을 표시")
        String description) {}
