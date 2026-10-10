package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 마감 모달 (BY-828) — 안 본 기록과 누적 메달 수. */
@Schema(description = "마감 모달 — 안 본 기록과 누적 메달 수")
public record RankingUnseenResponse(
        @Schema(description = "누적 메달 수(본 것 포함)") long total,

        @Schema(description = "안 본 기록 — 순위 → 일·주·월 → 순공·집중률·시간대 순")
        List<RankingRecordItem> records) {}
