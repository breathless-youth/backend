package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import project.study.studysession.entity.StudySession;

public record StudySessionSummaryResponse(
        @Schema(description = "세션 ID", example = "10") Long id,

        @Schema(description = "통계 귀속 날짜 — 한국 시간 기준 시작 날짜", example = "2026-07-24")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 공부 시간(초)", example = "6600")
        Integer studySec,

        @Schema(description = "순공 시간(초)", example = "6000") Integer focusSec,

        @Schema(description = "집중률(%) — 순공시간 ÷ 총시간 × 100, 소수 1자리 반올림", example = "91.7")
        Double focusRate) {

    // focusRate 계산은 서비스가 담당한다 — DTO는 값을 옮겨 담기만 한다
    public static StudySessionSummaryResponse from(StudySession session, double focusRate) {
        return new StudySessionSummaryResponse(
                session.getId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getStudySec(),
                session.getFocusSec(),
                focusRate);
    }
}
