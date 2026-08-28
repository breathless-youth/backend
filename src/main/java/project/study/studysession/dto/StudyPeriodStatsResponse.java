package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record StudyPeriodStatsResponse(
        @Schema(description = "조회 구간 시작일(포함)", example = "2026-08-24")
        LocalDate from,

        @Schema(description = "조회 구간 종료일(포함)", example = "2026-08-30")
        LocalDate to,

        @Schema(description = "기간 총 공부 시간(초) 합계", example = "47700")
        Long totalStudySec,

        @Schema(description = "기간 순공 시간(초) 합계", example = "41040")
        Long totalFocusSec,

        @Schema(description = "직전 비교 구간(compareFrom~compareTo)의 순공 합계(초). compare 미지정 시 null", example = "33840")
        Long previousTotalFocusSec,

        @Schema(description = "from~to 모든 날짜의 일별 집계 — 공부 없는 날도 0으로 채워 오름차순으로 담긴다")
        List<DailyStudyStat> dailyFocusSec) {}
