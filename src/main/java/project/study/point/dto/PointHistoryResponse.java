package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PointHistoryResponse(
        @Schema(description = "최신순 기록") List<PointHistoryItem> items,

        @Schema(description = "다음 쪽의 cursor. 더 없으면 null", example = "98")
        Long nextCursor) {}
