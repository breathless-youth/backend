package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 마감 기록 요약 (BY-828) — 메달 버튼·시트. 기록이 없을 때만 역대 최고와 메달에 가장 가까운 판을 준다. */
@Schema(description = "마감 기록 요약 — 메달 버튼·시트")
public record RankingRecordSummaryResponse(
        @Schema(description = "누적 메달 수") long total,
        long firstCount,
        long secondCount,
        long thirdCount,
        @Schema(description = "최근 기록 6개 — 모두 보기와 같은 순서") List<RankingRecordItem> recent,

        @Schema(description = "total이 0일 때만: 역대 마감 최고 순위 — 없으면 null")
        Best best,

        @Schema(description = "total이 0일 때만: 메달까지 남은 양이 가장 작은 진행 중 시간 판")
        Closest closest) {

    public record Best(int rank, RankingBoardType type, RankingPeriod period, TimeSlot slot, LocalDate periodStart) {}

    @Schema(
            description = "gap = max(0, 3위 값 − 내 값, 1800 − 내 값) 초 — 순공 일·주·월, 시간대 일·주 × 5구간 중 가장 작은 것. "
                    + "지금부터 더 쌓을 수 있는 판만 — 오늘 이미 지난 구간의 일간판은 메달권(gap 0)일 때만")
    public record Closest(RankingBoardType type, RankingPeriod period, TimeSlot slot, long gap) {}
}
