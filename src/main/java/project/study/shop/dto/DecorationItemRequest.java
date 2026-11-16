package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record DecorationItemRequest(
        @Schema(description = "적용할 상품 ID. null이면 기본으로 되돌린다", example = "31")
        Long itemId) {}
