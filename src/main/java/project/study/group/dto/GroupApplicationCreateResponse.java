package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record GroupApplicationCreateResponse(
        @Schema(description = "만들어진 신청 ID — 신청 취소에 쓴다", example = "55")
        long applicationId,

        @Schema(description = "신청 만료 시각(UTC ISO-8601) — 신청한 지 3일 뒤. 이때까지 답이 없으면 취소된다", example = "2026-11-06T05:30:00Z")
        Instant expiresAt) {}
