package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 완료한 할 일 1건 — 완료 시각을 함께 준다. 앱이 이 시각으로 플래너의 하루(05시 기준)에 붙인다 (ADR-0026). */
public record CompletedTaskItem(
        @Schema(description = "할 일 ID", example = "12") Long id,

        @Schema(description = "할 일 이름", example = "3단원 문제풀기")
        String name,

        @Schema(description = "과목 ID — 응답의 subjects[]에서 이름·색을 찾는다", example = "3")
        Long subjectId,

        @Schema(description = "완료 시각 (UTC, ISO-8601)", example = "2026-10-04T12:30:00Z")
        Instant doneAt,

        @Schema(description = "지운 할 일이면 true — 과목을 지워 함께 지워진 것도 포함", example = "false")
        boolean deleted) {}
