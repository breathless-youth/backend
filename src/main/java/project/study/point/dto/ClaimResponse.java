package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClaimResponse(
        @Schema(description = "이번 요청으로 지급됐는지. 이미 받은 보상을 다시 요청하면 false")
        boolean granted,

        @Schema(description = "이번 요청으로 받은 포인트. granted가 false면 0", example = "10")
        int points,

        @Schema(description = "요청 후 잔액", example = "1250") int balance) {}
