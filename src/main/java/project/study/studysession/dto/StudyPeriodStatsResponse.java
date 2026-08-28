package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record StudyPeriodStatsResponse(
        @Schema(description = "조회 구간 시작일(포함)", example = "2026-08-24")
        LocalDate from,

        @Schema(description = "조회 구간 종료일(포함)", example = "2026-08-30")
        LocalDate to,

        @Schema(description = "비교 구간 시작일(포함) — 요청의 compareFrom 에코, compare 미지정 시 null", example = "2026-08-17")
        LocalDate compareFrom,

        @Schema(description = "비교 구간 종료일(포함) — 요청의 compareTo 에코, compare 미지정 시 null", example = "2026-08-23")
        LocalDate compareTo,

        @Schema(description = "from~to 모든 날짜의 일별 집계 — 공부 없는 날도 0으로 채워 오름차순으로 담긴다")
        List<DailyStudyStat> dailyList,

        @Schema(description = "compareFrom~compareTo 모든 날짜의 일별 집계 — 0으로 채워 오름차순. compare 미지정 시 빈 배열")
        List<DailyStudyStat> compareDailyList) {}
