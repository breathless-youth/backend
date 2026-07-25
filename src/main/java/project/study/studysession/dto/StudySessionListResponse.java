package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.studysession.entity.EventStatus;

public record StudySessionListResponse(
        @Schema(description = "조회 범위 내 세션 요약 목록 — 시작 시각 내림차순 (없으면 빈 배열)")
        List<StudySessionSummaryResponse> sessions,

        @Schema(description = "조회 범위 내 세션 개수 — sessions 배열 길이와 같다 (자정 분할 세션은 각각 1개로 센다)", example = "2")
        Integer sessionCount,

        @Schema(description = "조회 범위 전체 총 공부 시간(초) 합계", example = "7200")
        Long totalStudySec,

        @Schema(description = "조회 범위 전체 순공 시간(초) 합계", example = "6000")
        Long totalFocusSec,

        @Schema(description = "조회 범위 전체 집중률(%) — totalFocusSec ÷ totalSessionSec × 100, 소수 1자리", example = "83.3")
        Double focusRate,

        @Schema(description = "조회 범위 전체 상태별 이벤트 발생 건수 — 없는 상태는 0으로 내려간다")
        Map<EventStatus, Long> eventCounts,

        @Schema(description = "date가 속한 달 동안 공부 기록이 있는 날짜 목록 — 캘린더 표시용 (중복 없음, 오름차순, 없으면 빈 배열)")
        List<LocalDate> studiedDatesInMonth) {}
