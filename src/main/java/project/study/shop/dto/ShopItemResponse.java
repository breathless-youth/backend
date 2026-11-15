package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopItemResponse(
        @Schema(description = "상품 ID", example = "12") long id,

        @Schema(description = "상품 코드. 앱이 그림·위치를 고를 때 쓰는 고정 이름", example = "STOPWATCH_HEAD_CROWN")
        String code,

        @Schema(description = "종류", example = "MASCOT_ACCESSORY")
        ShopCategory category,

        @Schema(description = "소품이 속한 마스코트 코드. 소품이 아니면 null", example = "STOPWATCH")
        String mascotCode,

        @Schema(description = "소품 부위(HEAD·FACE·BODY·WINGS·HELD·UNDERFOOT·EFFECT). 소품이 아니면 null", example = "HEAD")
        AccessorySlot slot,

        @Schema(description = "화면에서 거르는 분류 코드(타이머 배경의 NATURE 등). 보여 줄 이름은 앱이 정한다. 없으면 null", example = "NATURE")
        String tag,

        @Schema(description = "이름. Accept-Language의 언어, 번역이 없으면 한국어", example = "왕관")
        String name,

        @Schema(description = "그림 주소. 아직 없으면 null") String imageUrl,

        @Schema(description = "마스코트 뒤에 그리는 조각의 그림 주소. 뒤 조각이 없는 상품이면 null")
        String backImageUrl,

        @Schema(description = "등급. 1·2·3", example = "1") int grade,

        @Schema(description = "가격(P). 포인트로 사는 상품이 아니면(보상 상품·결제 상품) null", example = "600")
        Integer price,

        @Schema(description = "가졌는지. 보상 상품은 조건을 채우면 true") boolean owned,

        @Schema(description = "쓰는 중인지(착용·적용)") boolean inUse,

        @Schema(description = "보상 상품의 조건과 진행. 보상 상품이 아니면 null")
        ShopItemUnlock unlock,

        @Schema(
                description = "스토어 결제로 사는 상품의 스토어 상품 ID. RevenueCat으로 이 ID를 결제하면 서버가 알림을 받아 보유 처리한다(owned가 true가 된다)."
                        + " 이 상품은 price와 unlock이 null이고 포인트로 살 수 없다. 결제 상품이 아니면 null",
                example = "item_crown_gold")
        String storeProductId) {}
