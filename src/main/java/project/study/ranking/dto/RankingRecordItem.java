package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 마감 기록 항목 (BY-828) — 1·2·3위로 마감한 판 하나. */
@Schema(description = "마감 기록 항목 — 1·2·3위로 마감한 판 하나")
public record RankingRecordItem(
        long id,
        RankingBoardType type,
        RankingPeriod period,
        @Schema(description = "시간대 판만, 아니면 null") TimeSlot slot,
        @Schema(description = "기간 시작일(KST)") LocalDate periodStart,
        @Schema(description = "1·2·3") int rank,
        @Schema(description = "시간 판 초, 집중률 %(소수 1자리)") Number value,
        @Schema(description = "마감 시각") Instant closesAt) {}
