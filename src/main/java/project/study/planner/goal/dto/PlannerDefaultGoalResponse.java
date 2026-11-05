package project.study.planner.goal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlannerDefaultGoalResponse(
        @Schema(description = "기본 목표 초. null은 미설정, 0은 명시적 0초", example = "3600", nullable = true)
        Integer goalSec) {}
