package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MascotResponse(
        @Schema(description = "마스코트 코드. 이름과 그림은 앱이 이 코드로 고른다", example = "LAMP")
        String code,

        @Schema(description = "여는 방법. FOCUS_TIME(누적 순공)·POINTS(포인트로 사기)", example = "FOCUS_TIME")
        MascotUnlockType unlockType,

        @Schema(description = "해금 기준 누적 순공(초). unlockType이 POINTS면 0", example = "108000")
        int unlockFocusSec,

        @Schema(description = "가격(P). unlockType이 FOCUS_TIME이면 null", example = "3000")
        Integer price,

        @Schema(description = "열렸는지. POINTS면 샀는지(가졌는지)다") boolean unlocked,

        @Schema(description = "열릴 때까지 남은 순공(초). 열렸거나 unlockType이 POINTS면 0", example = "15120")
        long remainingFocusSec,

        @Schema(description = "지금 선택한 마스코트인지") boolean selected,

        @Schema(description = "열렸는데 아직 축하 화면을 안 본 마스코트인지. 처음부터 열려 있는 마스코트와 포인트로 산 마스코트는 늘 false")
        boolean newlyUnlocked) {}
