package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NextMascot(
        @Schema(description = "다음에 열릴 마스코트 코드", example = "LAMP")
        String code,

        @Schema(description = "해금 기준 누적 순공(초)", example = "108000")
        int unlockFocusSec,

        @Schema(description = "열릴 때까지 남은 순공(초)", example = "10080")
        long remainingFocusSec) {}
