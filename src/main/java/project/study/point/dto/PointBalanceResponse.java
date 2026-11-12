package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PointBalanceResponse(
        @Schema(description = "가진 포인트(무료와 결제 몫의 합)", example = "1240")
        int balance,

        @Schema(description = "그 가운데 결제로 산 몫. 쓸 때는 무료 몫부터 빠진다", example = "1000")
        int paidBalance) {}
