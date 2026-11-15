package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 효과 그림의 조각 하나. 위치와 크기는 그림 판에 대한 비율(0~1)이다. */
public record EffectPiece(
        @Schema(description = "조각의 왼쪽 위치(판 폭에 대한 비율)", example = "0.12")
        double x,

        @Schema(description = "조각의 위쪽 위치(판 높이에 대한 비율)", example = "0.3")
        double y,

        @Schema(description = "조각의 폭(판 폭에 대한 비율)", example = "0.2")
        double width,

        @Schema(description = "조각의 높이(판 높이에 대한 비율)", example = "0.2")
        double height,

        @Schema(
                description = "움직임 종류. float·fall·rise·twinkle·flutter·pulse 등. 앱이 모르는 값은 멈춘 그림으로 그린다",
                example = "float")
        String motion) {}
