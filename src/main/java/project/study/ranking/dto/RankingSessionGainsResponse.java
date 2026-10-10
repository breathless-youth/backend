package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 세션 뒤 오른 랭킹 (BY-828 §7.3) — 이번 제출 전보다 순위가 오른 판만. */
@Schema(description = "세션 뒤 오른 랭킹 — 오른 판만")
public record RankingSessionGainsResponse(
        @Schema(description = "주간 순공 — 오르지 않았으면 null") Gain weekly,

        @Schema(description = "나머지 오른 판 — 순공 일·월, 집중률 주·월, 시간대 일·주, 명예의 전당 순")
        List<BoardGain> others) {

    public record Gain(
            @Schema(description = "이번 제출을 뺀 내 값의 순위") int before,
            @Schema(description = "지금 순위") int after,
            int delta) {}

    public record BoardGain(
            RankingBoardType type,
            @Schema(description = "명예의 전당은 null") RankingPeriod period,
            @Schema(description = "시간대 판만") TimeSlot slot,
            int before,
            int after,
            int delta) {}
}
