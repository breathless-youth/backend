package project.study.planner.goal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlannerGoalResponse(
        @Schema(description = "적용 목표. 개별값이 있으면 개별값, 없으면 기본값. 둘 다 없을 때만 null", example = "7200", nullable = true)
        Integer goalSec,

        @Schema(description = "이 날짜의 개별 목표. null은 기본값 상속, 0은 명시적 0초", example = "7200", nullable = true)
        Integer individualGoalSec,

        @Schema(description = "사용자의 기본 목표. null은 미설정", example = "3600", nullable = true)
        Integer defaultGoalSec) {}
