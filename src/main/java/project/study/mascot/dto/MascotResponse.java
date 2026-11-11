package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MascotResponse(
        @Schema(description = "마스코트 코드. 이름과 그림은 앱이 이 코드로 고른다", example = "LAMP")
        String code,

        @Schema(description = "해금 기준 누적 순공(초)", example = "108000")
        int unlockFocusSec,

        @Schema(description = "열렸는지") boolean unlocked,

        @Schema(description = "열릴 때까지 남은 순공(초). 열렸으면 0", example = "15120")
        long remainingFocusSec,

        @Schema(description = "지금 선택한 마스코트인지") boolean selected,

        @Schema(description = "열렸는데 아직 축하 화면을 안 본 마스코트인지. 처음부터 열려 있는 마스코트는 늘 false")
        boolean newlyUnlocked) {}
