package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import project.study.studysession.entity.StudySession;

public record StudySessionSummaryResponse(
        @Schema(description = "세션 ID — 상세는 GET /api/study-sessions/{id} 로 조회", example = "10")
        Long id,

        @Schema(description = "통계 귀속 날짜 — 한국 시간 기준 시작 날짜", example = "2026-07-24")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 세션 시간(초)", example = "7200")
        Integer sessionSec,

        @Schema(description = "순공 시간(초)", example = "6600") Integer focusSec) {

    public static StudySessionSummaryResponse from(StudySession session) {
        return new StudySessionSummaryResponse(
                session.getId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getSessionSec(),
                session.getFocusSec());
    }
}
