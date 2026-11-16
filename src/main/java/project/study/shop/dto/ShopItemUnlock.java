package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopItemUnlock(
        @Schema(
                description = "조건 종류. STREAK_DAYS(연속 공부 일수, 최고 기록 기준)·TOTAL_FOCUS_HOURS(측정한 누적 순공 시간)",
                example = "STREAK_DAYS")
        UnlockType type,

        @Schema(description = "채워야 하는 값(일 또는 시간)", example = "30")
        int requiredValue,

        @Schema(description = "지금 값(일 또는 시간). 조건을 채운 뒤에는 requiredValue보다 클 수 있다", example = "12")
        long currentValue) {}
