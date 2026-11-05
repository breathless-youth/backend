package project.study.planner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public record PlannerRepeatResponse(
        @Schema(description = "반복 계열 ID. 일반 할 일이면 null") Long seriesId,

        @Schema(description = "현재 계획 날짜에 유효한 규칙의 시작일. 반복 안 함이면 null")
        LocalDate startDate,

        @Schema(description = "월~일 순서의 반복 요일. 일반 할 일/종료된 계열의 예외는 빈 배열")
        List<DayOfWeek> weekdays,

        @Schema(description = "규칙 종료일(포함). 종료일 없음 또는 반복 안 함이면 null")
        LocalDate endDate) {}
