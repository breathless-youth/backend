package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "선택 언어 번역 — 각 필드가 null이면 기존 원문 필드를 표시한다")
public record GroupTranslation(String language, String name, String description) {}
