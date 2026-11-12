package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PointProduct(
        @Schema(description = "충전 상품 ID", example = "1") long id,

        @Schema(description = "스토어 상품 ID. 앱스토어·구글플레이·RevenueCat에 등록한 것과 같다", example = "points_1000")
        String storeProductId,

        @Schema(description = "결제로 사는 포인트", example = "1000")
        int points,

        @Schema(description = "덤으로 주는 포인트. 없으면 0", example = "100")
        int bonusPoints,

        @Schema(description = "받는 포인트 합계(points + bonusPoints)", example = "1100")
        int totalPoints) {}
