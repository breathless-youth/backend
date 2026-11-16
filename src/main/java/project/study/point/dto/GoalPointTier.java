package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 하루 목표 보상의 한 구간. 목표 시간이 fromSec 이상 toSec 미만인 부분의 값이다. */
public record GoalPointTier(
        @Schema(description = "구간이 시작하는 목표 시간(초)", example = "0")
        int fromSec,

        @Schema(description = "구간이 끝나는 목표 시간(초). 마지막 구간의 끝을 넘는 목표 시간은 세지 않는다", example = "7200")
        int toSec,

        @Schema(description = "목표 시간의 단위(unitSec)마다 주는 포인트", example = "20")
        int points) {}
