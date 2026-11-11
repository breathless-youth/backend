package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import project.study.mascot.dto.NextMascot;

public record StudyRewardResponse(
        @Schema(description = "이 세션 제출로 받은 포인트. 하루 상한을 다 채웠으면 0", example = "20")
        int earnedPoints,

        @Schema(description = "아래 인정 순공의 날짜. 세션의 마지막 조각 날짜이고 세션이 없으면 오늘", example = "2026-11-10")
        LocalDate statDate,

        @Schema(description = "그날 포인트로 인정된 순공(초). 측정한 순공만 세고(직접 입력 제외) 상한을 넘지 않는다", example = "7800")
        int recognizedFocusSec,

        @Schema(description = "하루 인정 상한(초)", example = "43200")
        int dailyCapFocusSec,

        @Schema(description = "다음에 열릴 마스코트. 다 열었으면 null") NextMascot nextMascot) {}
