package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PointProductsResponse(
        @Schema(description = "파는 충전 상품. 정해진 순서대로") List<PointProduct> items) {}
