package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.studysession.entity.StudySession;

public record StudySessionResponse(
        @Schema(description = "세션 ID", example = "10") Long id,
        @Schema(description = "세션 주인의 유저 ID", example = "1") Long userId,

        @Schema(description = "통계 귀속 날짜 — 한국 시간 기준 시작 날짜 (자정을 걸쳐도 시작일에 통째로 귀속)", example = "2026-07-24")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 세션 시간(초) — 서버가 계산: 종료 - 시작", example = "7200")
        Integer sessionSec,

        @Schema(description = "순공 시간(초) — 서버가 계산: sessionSec - 비공부 이벤트 구간 합", example = "6600")
        Integer focusSec,

        @Schema(description = "집중률(%) — 순공시간 ÷ 총시간 × 100, 소수 1자리 반올림", example = "91.7")
        Double focusRate,

        @Schema(description = "비공부 상태 이벤트 목록 — 시작 시각 오름차순") List<StatusEventResponse> events) {

    public static StudySessionResponse from(StudySession session) {
        return new StudySessionResponse(
                session.getId(),
                session.getUserId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getSessionSec(),
                session.getFocusSec(),
                session.focusRate(),
                session.getEvents().stream().map(StatusEventResponse::from).toList());
    }
}
