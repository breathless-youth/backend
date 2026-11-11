package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MedalClaimAllResponse(
        @Schema(description = "이번 요청으로 포인트를 받은 메달 수. 받을 메달이 없었으면 0", example = "2")
        int claimedCount,

        @Schema(description = "이번 요청으로 받은 포인트 합계", example = "130")
        int points,

        @Schema(description = "요청 후 잔액", example = "1370") int balance) {}
