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

        @Schema(description = "소품 부위. 소품이 아니면 null", example = "HEAD")
        AccessorySlot slot,

        @Schema(description = "화면에서 거르는 분류 코드(타이머 배경의 NATURE 등). 보여 줄 이름은 앱이 정한다. 없으면 null", example = "NATURE")
        String tag,

        @Schema(description = "이름. Accept-Language의 언어, 번역이 없으면 한국어", example = "왕관")
        String name,

        @Schema(description = "그림 주소. 아직 없으면 null") String imageUrl,

        @Schema(description = "가격(P). 포인트로 살 수 없는 보상 상품이면 null", example = "600")
        Integer price,

        @Schema(description = "가졌는지. 보상 상품은 조건을 채우면 true") boolean owned,

        @Schema(description = "쓰는 중인지(착용·적용)") boolean inUse,

        @Schema(description = "보상 상품의 조건과 진행. 포인트로 사는 상품이면 null")
        ShopItemUnlock unlock) {}
