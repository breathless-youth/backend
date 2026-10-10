package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/**
 * 랭킹판 조회 응답 (BY-828). 값·차이 단위는 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리). 다른 사용자의 userId는 싣지 않는다 —
 * 행 키는 고유한 nickname이다. 판에 해당하지 않는 추가 필드는 null이다.
 */
@Schema(description = "랭킹판 조회 응답")
public record RankingBoardResponse(
        RankingBoardType type,
        RankingPeriod period,
        TimeSlot slot,
        @Schema(description = "기간 시작일(KST) — 명예의 전당은 null") LocalDate periodStart,
        @Schema(description = "마감 시각 — 명예의 전당은 null") Instant closesAt,

        @Schema(description = "값 기준 시각 — focusing인 값은 이 시각부터 1초씩 올려 보간한다")
        Instant asOf,

        @Schema(description = "1·2·3위 (참가자가 적으면 그만큼)") List<BoardEntry> podium,
        @Schema(description = "내 순위 — 참가하지 않았으면 null") BoardMe me,

        @Schema(description = "앞 2 · 나 · 뒤 2 — 앞이 모자라면 뒤를 더. 연속 일수 판은 []")
        List<BoardEntry> around,

        @Schema(description = "바로 위 — 1위면 null") BoardNeighbor above,
        @Schema(description = "바로 아래 — 꼴찌면 null") BoardNeighbor below,

        @Schema(description = "me가 null일 때만: 지금 시작하면 받을 순위(참가자 + 1) — 집중률 판·지난 기간(offset=-1)은 null")
        Integer startNowRank,

        @Schema(description = "집중률 판만: 참가 조건 진행도") RateEligibility eligibility,

        @Schema(description = "누적 시간·누적 일수 판만: 다음 상위 % 구간까지")
        NextTier nextTier,

        @Schema(description = "연속 공부 일수 판만: 최고 기록과 지금 연속") StreakCard streak,

        @Schema(description = "연속 공부 일수 판만: 같은 일수 묶음 앞 2 · 내 묶음 · 뒤 2")
        List<StreakGroupRow> aroundGroups,

        @Schema(description = "순공 주간 판에 me가 null일 때만: 지난주 분포의 구간별 필요 순공")
        List<GoalExample> goalExamples) {

    public record BoardEntry(int rank, String nickname, Number value, boolean focusing, boolean me) {}

    public record BoardMe(int rank, Number value, boolean focusing, int topPercent) {}

    public record BoardNeighbor(String nickname, Number gap, boolean focusing, Long catchUpFocusSec) {}

    public record RateEligibility(
            boolean eligible, long focusSec, long requiredFocusSec, double focusRate, Integer expectedRank) {}

    public record NextTier(int percent, long remaining, Integer etaDays) {}

    public record StreakCard(
            int maxDays,
            LocalDate maxStart,
            LocalDate maxEnd,
            int currentDays,
            boolean currentIsBest,
            Integer nextRankIfContinue) {}

    public record StreakGroupRow(
            int days,
            int rank,
            String nickname,
            int othersCount,
            LocalDate achievedDate,
            boolean me,
            Integer myOrder) {}

    public record GoalExample(int percent, long value) {}
}
