package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record AccessoryEquipRequest(
        @Schema(description = "부위", example = "HEAD") @NotNull
        AccessorySlot slot,

        @Schema(description = "낄 소품의 상품 ID. null이면 그 부위를 벗는다", example = "12")
        Long itemId) {}
