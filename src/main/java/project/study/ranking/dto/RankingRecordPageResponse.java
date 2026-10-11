package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 마감 기록 목록 (BY-828) — (마감 시각, id) 내림차순. */
@Schema(description = "마감 기록 목록 — (마감 시각, id) 내림차순")
public record RankingRecordPageResponse(
        List<RankingRecordItem> items,
        @Schema(description = "다음 페이지 커서 — 마지막 페이지면 null") String nextCursor) {}
