package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "선택 언어로 옮긴 신청 소개 글 — 없으면 기존 원문 introduction을 표시한다")
public record GroupApplicationTranslation(
        @Schema(
                allowableValues = {"ko", "en", "ja", "zh-TW"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String language,

        @Schema(description = "번역 소개 글", requiredMode = Schema.RequiredMode.REQUIRED)
        String introduction) {}
