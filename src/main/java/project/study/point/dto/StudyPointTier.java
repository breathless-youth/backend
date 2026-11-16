package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 공부 포인트의 한 구간. 하루 순공이 fromSec 이상 toSec 미만인 동안의 값이다. */
public record StudyPointTier(
        @Schema(description = "구간이 시작하는 하루 순공(초)", example = "0")
        int fromSec,

        @Schema(description = "구간이 끝나는 하루 순공(초). 마지막 구간의 끝이 하루 상한이다", example = "7200")
        int toSec,

        @Schema(description = "혼자 공부할 때 단위(unitSec)마다 주는 포인트", example = "7")
        int soloPoints,

        @Schema(description = "같이 공부할 때(방에 나 말고 한 명 이상 있던 시간) 단위마다 주는 포인트", example = "10")
        int togetherPoints) {}
