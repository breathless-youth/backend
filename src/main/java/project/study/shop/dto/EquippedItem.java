package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EquippedItem(
        @Schema(description = "상품 ID", example = "12") long itemId,

        @Schema(description = "상품 코드", example = "STOPWATCH_HEAD_CROWN")
        String code,

        @Schema(description = "그림 주소. 아직 없으면 null") String imageUrl) {

    public static EquippedItem from(ShopItem item) {
        return item == null ? null : new EquippedItem(item.id(), item.code(), item.imageUrl());
    }
}
