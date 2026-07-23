package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import project.study.studysession.entity.EventStatus;

public record StudySessionListResponse(
        @Schema(description = "기간 내 세션 요약 목록 — 시작 시각 내림차순 (없으면 빈 배열)")
        List<StudySessionSummaryResponse> sessions,

        @Schema(description = "기간 전체 총 세션 시간(초) 합계", example = "7200")
        Long totalSessionSec,

        @Schema(description = "기간 전체 순공 시간(초) 합계", example = "6000")
        Long totalFocusSec,

        @Schema(description = "기간 전체 집중률(%) — totalFocusSec ÷ totalSessionSec × 100, 소수 1자리", example = "83.3")
        Double focusRate,

        @Schema(description = "기간 전체 상태별 이벤트 발생 건수 — 없는 상태는 0으로 내려간다")
        Map<EventStatus, Long> eventCounts) {}
