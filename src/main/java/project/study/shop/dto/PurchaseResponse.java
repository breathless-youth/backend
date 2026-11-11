package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PurchaseResponse(
        @Schema(description = "산 상품 ID", example = "12") long itemId,
        @Schema(description = "구매 후 잔액", example = "640") int balance) {}
