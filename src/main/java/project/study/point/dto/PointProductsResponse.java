package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PointProductsResponse(
        @Schema(description = "파는 충전 상품. 정해진 순서대로") List<PointProduct> items,

        @Schema(
                description = "RevenueCat에 로그인할 때 쓰는 내 결제용 ID (BY-1299). `Purchases.logIn`에 회원번호 대신 이 값을 넣는다. "
                        + "비회원은 결제할 수 없어 null",
                example = "0b9f6a3e-6c1d-4d2a-9a51-3f1c2b7e8d90",
                nullable = true)
        String revenueCatAppUserId) {}
