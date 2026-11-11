package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PointBalanceResponse(
        @Schema(description = "가진 포인트", example = "1240") int balance) {}
