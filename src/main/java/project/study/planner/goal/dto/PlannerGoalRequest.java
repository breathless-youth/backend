package project.study.planner.goal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlannerGoalRequest(
        @Schema(description = "목표 초. 0~86400, 1800초 단위. 0은 명시적인 목표이며 해제는 DELETE로 한다", example = "7200")
        @NotNull(message = "목표 초는 필수입니다")
        @Min(value = 0, message = "목표 초는 0 이상이어야 합니다")
        @Max(value = 86400, message = "목표 초는 86400 이하여야 합니다")
        Integer goalSec) {}
