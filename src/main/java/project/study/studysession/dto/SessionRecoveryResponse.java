package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 복구 판별·확인 API 응답 (BY-455) — 비정상 종료 후 홈 재진입 시, 자동 확정됐거나 방금 확정한 세션의 요약.
 * 자정을 걸친 세션은 조각들을 한 건으로 집계해 내려준다(시작=최소, 종료=최대, 시간=합).
 */
public record SessionRecoveryResponse(
        @Schema(description = "통계 귀속 날짜 — 한국 시간 기준 시작 날짜", example = "2026-08-27")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-08-27T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-08-27T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 공부 시간(초)", example = "6600")
        Integer studySec,

        @Schema(description = "순공 시간(초)", example = "6000") Integer focusSec) {}
