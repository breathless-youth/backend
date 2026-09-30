package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 저장된 세션의 과목 구간 1건 — 자정 분할 조각에는 잘린 구간이, 파생값에는 그 조각 이벤트로 계산한 값이 담긴다 (ADR-0023). */
public record SubjectSegmentResponse(
        @Schema(description = "과목 ID", example = "3") Long subjectId,

        @Schema(description = "구간 시작 시각 (UTC, ISO-8601)", example = "2026-09-22T00:12:00Z")
        Instant startedAt,

        @Schema(description = "구간 종료 시각 (UTC, ISO-8601)", example = "2026-09-22T00:41:00Z")
        Instant endedAt,

        @Schema(description = "이 구간의 총 공부 시간(초) — 서버 계산: 구간 길이 − PAUSE 겹침", example = "1740")
        Integer studySec,

        @Schema(description = "이 구간의 순공 시간(초) — 서버 계산: 구간 길이 − 모든 비공부 이벤트 겹침", example = "1620")
        Integer focusSec) {
    public static SubjectSegmentResponse from(StudySessionSubjectSegment segment) {
        return new SubjectSegmentResponse(
                segment.getSubjectId(),
                segment.getStartedAt(),
                segment.getEndedAt(),
                segment.getStudySec(),
                segment.getFocusSec());
    }
}
