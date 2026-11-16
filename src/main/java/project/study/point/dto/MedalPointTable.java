package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 메달 포인트 표. 종목은 가르지 않고 기간과 순위로만 정한다. */
public record MedalPointTable(
        @Schema(description = "일간 메달 포인트, 1·2·3위 순", example = "[100, 50, 30]")
        List<Integer> daily,

        @Schema(description = "주간 메달 포인트, 1·2·3위 순", example = "[300, 150, 100]")
        List<Integer> weekly,

        @Schema(description = "월간 메달 포인트, 1·2·3위 순", example = "[1000, 500, 300]")
        List<Integer> monthly) {}
