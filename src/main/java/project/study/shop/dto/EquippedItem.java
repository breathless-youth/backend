package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record EquippedItem(
        @Schema(description = "상품 ID", example = "12") long itemId,

        @Schema(description = "상품 코드", example = "STOPWATCH_HEAD_CROWN")
        String code,

        @Schema(description = "그림 주소. 아직 없으면 null") String imageUrl,

        @Schema(description = "마스코트 뒤에 그리는 조각의 그림 주소. 뒤 조각이 없으면 null")
        String backImageUrl,

        @Schema(description = "세트의 움직이는 효과 그림 주소. 없으면 null") String effectImageUrl,

        @Schema(description = "효과 그림의 조각별 위치와 움직임. 효과 칸 소품은 imageUrl에, 세트는 effectImageUrl에 적용한다. 없으면 null")
        List<EffectPiece> effectPieces,

        @Schema(
                description = "그림 없이 앱이 코드로 그리는 빛 효과의 종류(aurora·glow·rainbow 등). 이때 imageUrl은 null. 아니면 null",
                example = "aurora")
        String effectType) {

    public static EquippedItem from(ShopItem item) {
        return item == null
                ? null
                : new EquippedItem(
                        item.id(),
                        item.code(),
                        item.imageUrl(),
                        item.backImageUrl(),
                        item.effectImageUrl(),
                        item.effectPieces(),
                        item.effectType());
    }
}
