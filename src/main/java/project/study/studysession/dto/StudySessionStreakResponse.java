package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StudySessionStreakResponse(
        @Schema(description = "현재 연속 공부일 — 오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다", example = "5")
        int streak,

        @Schema(description = "역대 최장 연속 공부일", example = "12")
        int maxStreak) {}
