package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TaskResponse(
        @Schema(description = "할 일 ID", example = "12") Long id,

        @Schema(description = "할 일 이름", example = "3단원 문제풀기")
        String name,

        @Schema(
                description = "완료 시각 (UTC, ISO-8601) — 미완료면 null. 완료한 날(KST)이 지나면 목록에서 빠진다",
                example = "2026-09-20T05:12:00Z")
        Instant doneAt) {}
