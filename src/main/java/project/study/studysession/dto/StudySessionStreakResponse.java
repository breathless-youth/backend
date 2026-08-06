package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record StudySessionStreakResponse(
        @Schema(description = "현재 연속 공부일 — 오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다", example = "5")
        int streak,

        @Schema(description = "역대 최장 연속 공부일", example = "12")
        int maxStreak,

        @Schema(
                description =
                        "from~to 기간 중 스트릭 인정 기준(순공시간 10분 이상인 세션이 하루라도 있음)을 만족한 날짜 목록 " + "— 오름차순, from/to 미지정 시 빈 배열",
                example = "[\"2026-07-20\", \"2026-07-22\", \"2026-07-27\"]")
        List<LocalDate> studiedDatesInRange) {}
