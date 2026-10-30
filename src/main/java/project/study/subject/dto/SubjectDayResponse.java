package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 날짜 기준 과목 1건 (BY-1053, ADR-0038) — 그날 있던 과목에 그날(05시~다음 날 05시)의 시간과 완료 여부를 붙인다.
 * 할 일은 싣지 않는다 — 일간 조회 {@code GET /api/stats}의 {@code subjects[].tasks}가 그대로 출처다.
 */
public record SubjectDayResponse(
        @Schema(description = "과목 ID", example = "3") Long id,
        @Schema(description = "과목 이름", example = "수학") String name,

        @Schema(description = "색 팔레트 인덱스(0..19) — GET /api/subjects의 colorIndex와 같다", example = "2")
        int colorIndex,

        @Schema(description = "지금 지운 과목이면 true — 그날에는 있었던 과목이라 실린다", example = "false")
        boolean deleted,

        @Schema(description = "그날(05시~다음 날 05시 KST) 이 과목의 총 공부 시간(초) — 세션 과목 구간을 그 창으로 잘라 합산", example = "3600")
        long studySec,

        @Schema(description = "그날 이 과목의 순공 시간(초)", example = "3300")
        long focusSec,

        @Schema(description = "그 날짜에 완료 표시했으면 true — 날짜별 기록이라 다음 날에는 다시 false다", example = "true")
        boolean completed,

        @Schema(
                description = "완료 표시한 시각 (UTC, ISO-8601) — 완료가 아니면 null",
                example = "2026-10-29T12:30:00Z",
                nullable = true)
        Instant completedAt) {}
