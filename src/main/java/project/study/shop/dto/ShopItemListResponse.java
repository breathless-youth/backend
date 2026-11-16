package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ShopItemListResponse(
        @Schema(description = "파는 중인 전체 상품. 종류, 진열 순서 순") List<ShopItemResponse> items) {}
