package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MascotPurchaseResponse(
        @Schema(description = "산 마스코트 코드", example = "CAMCORDER")
        String code,

        @Schema(description = "구매 후 잔액", example = "640") int balance) {}
