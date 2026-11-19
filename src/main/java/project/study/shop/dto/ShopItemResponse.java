package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ShopItemResponse(
        @Schema(description = "상품 ID", example = "12") long id,

        @Schema(description = "상품 코드. 앱이 그림·위치를 고를 때 쓰는 고정 이름", example = "STOPWATCH_HEAD_CROWN")
        String code,

        @Schema(description = "종류", example = "MASCOT_ACCESSORY")
        ShopCategory category,

        @Schema(description = "소품이 속한 마스코트 코드. 소품이 아니면 null", example = "STOPWATCH")
        String mascotCode,

        @Schema(
                description =
                        "소품 부위(HEAD·FACE·BODY·WINGS·HELD·UNDERFOOT·EFFECT·SET). SET은 캐릭터의 한 벌 옷이고 스토어 결제로만 판다. 소품이 아니면 null",
                example = "HEAD")
        AccessorySlot slot,

        @Schema(description = "화면에서 거르는 분류 코드(타이머 배경의 NATURE 등). 보여 줄 이름은 앱이 정한다. 없으면 null", example = "NATURE")
        String tag,

        @Schema(description = "이름. Accept-Language의 언어, 번역이 없으면 한국어", example = "왕관")
        String name,

        @Schema(description = "그림 주소. 아직 없으면 null") String imageUrl,

        @Schema(description = "마스코트 뒤에 그리는 조각의 그림 주소. 뒤 조각이 없는 상품이면 null")
        String backImageUrl,

        @Schema(description = "등급. 1·2·3", example = "1") int grade,

        @Schema(description = "가격(P). 포인트로 사는 상품이 아니면(보상 상품·결제 상품·무료 상품) null", example = "600")
        Integer price,

        @Schema(description = "가졌는지. 보상 상품은 조건을 채우면 true, 무료 상품은 판매 중인 동안 모두 true")
        boolean owned,

        @Schema(description = "쓰는 중인지(착용·적용)") boolean inUse,

        @Schema(description = "보상 상품의 조건과 진행. 보상 상품이 아니면 null")
        ShopItemUnlock unlock,

        @Schema(
                description = "스토어 결제로 사는 상품의 스토어 상품 ID. RevenueCat으로 이 ID를 결제하면 서버가 알림을 받아 보유 처리한다(owned가 true가 된다)."
                        + " 이 상품은 price와 unlock이 null이고 포인트로 살 수 없다. 결제 상품이 아니면 null",
                example = "item_crown_gold")
        String storeProductId,

        @Schema(description = "세트의 움직이는 효과 그림 주소. 옷 그림(imageUrl)은 멈춰 있고 이 그림만 움직인다. 없으면 null")
        String effectImageUrl,

        @Schema(description = "효과 그림의 조각별 위치와 움직임. 효과 칸 소품은 imageUrl에, 세트는 effectImageUrl에 적용한다. 없으면 null")
        List<EffectPiece> effectPieces,

        @Schema(
                description = "그림 없이 앱이 코드로 그리는 빛 효과의 종류(aurora·glow·rainbow 등). 이때 imageUrl은 null. 아니면 null",
                example = "aurora")
        String effectType,

        @Schema(
                description = "공간 파일 위치. 앱이 종류(assetType)와 함께 기기별 파일 주소를 만든다. 공간이 아니면 null",
                example = "watcher/9c8d9f19")
        String assetKey,

        @Schema(description = "공간 파일의 종류(IMAGE·VIDEO). 공간이 아니면 null", example = "VIDEO")
        AssetType assetType) {}
