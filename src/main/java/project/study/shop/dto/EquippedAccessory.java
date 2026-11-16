package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EquippedAccessory(
        @Schema(description = "마스코트 코드", example = "STOPWATCH")
        String mascotCode,

        @Schema(description = "부위", example = "HEAD") AccessorySlot slot,
        @Schema(description = "낀 소품") EquippedItem item) {}
