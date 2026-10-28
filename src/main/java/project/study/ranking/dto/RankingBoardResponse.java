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

        @Schema(description = "1·2·3위 (참가자가 적으면 그만큼). 일수 판(누적 일수·연속 공부)은 [] — podiumGroups를 쓴다")
        List<BoardEntry> podium,

        @Schema(description = "내 순위 — 참가하지 않았으면 null. 일수 판의 rank·topPercent는 공동 순위(같은 일수면 같은 순위, 1·1·3)")
        BoardMe me,

        @Schema(description = "앞 2 · 나 · 뒤 2 — 앞이 모자라면 뒤를 더. 일수 판은 [] — aroundGroups를 쓴다")
        List<BoardEntry> around,

        @Schema(description = "바로 위 — 1위면 null. 일수 판은 나와 일수가 다른 가장 가까운 사람이라 gap이 0이 아니다")
        BoardNeighbor above,

        @Schema(description = "바로 아래 — 꼴찌면 null. 일수 판은 나와 일수가 다른 가장 가까운 사람이라 gap이 0이 아니다")
        BoardNeighbor below,

        @Schema(
                description = "me가 null일 때만: 지금 시작하면 받을 순위(참가자 + 1) — 일수 판은 1일이 되는 공동 순위(2일 이상인 사람 수 + 1). "
                        + "집중률 판·지난 기간(offset=-1)은 null")
        Integer startNowRank,

        @Schema(
                description = "집중률 판만: 참가 조건 진행도 — 미달이면 expectedRank·expectedTopPercent는 지금 집중률로 "
                        + "참가할 때의 순위·상위 %(나를 넣은 참가자 수 기준, me.topPercent와 같은 식). 충족이면 둘 다 null")
        RateEligibility eligibility,

        @Schema(description = "누적 시간·누적 일수 판만: 다음 상위 % 구간까지")
        NextTier nextTier,

        @Schema(description = "연속 공부 일수 판만: 최고 기록과 지금 연속") StreakCard streak,

        @Schema(description = "일수 판만: 같은 일수 묶음 앞 2 · 내 묶음 · 뒤 2. me가 null이면 []")
        List<DayGroupRow> aroundGroups,

        @Schema(description = "일수 판만: 일수 많은 순 묶음 3개(묶음이 적으면 그만큼). 내 참가와 상관없이 준다")
        List<DayGroupRow> podiumGroups,

        @Schema(description = "순공 주간 판에 me가 null일 때만: 지난주 분포의 구간별 필요 순공")
        List<GoalExample> goalExamples) {

    public record BoardEntry(
            int rank, String nickname, String profileImageUrl, Number value, boolean focusing, boolean me) {}

    public record BoardMe(int rank, Number value, boolean focusing, int topPercent) {}

    public record BoardNeighbor(
            String nickname, String profileImageUrl, Number gap, boolean focusing, Long catchUpFocusSec) {}

    public record RateEligibility(
            boolean eligible,
            long focusSec,
            long requiredFocusSec,
            double focusRate,
            Integer expectedRank,
            Integer expectedTopPercent) {}

    public record NextTier(int percent, long remaining, Integer etaDays) {}

    public record StreakCard(
            int maxDays,
            LocalDate maxStart,
            LocalDate maxEnd,
            int currentDays,
            boolean currentIsBest,
            Integer nextRankIfContinue) {}

    /**
     * 일수 판 묶음 한 줄 — rank는 공동 순위, nickname은 대표(내가 있으면 나, 없으면 먼저 달성한 사람), profileImageUrl은
     * 그 대표의 사진 경로, othersCount는 묶음 인원 − 1, achievedDate는 대표가 그 일수에 도달한 날. myOrder는 내 묶음에서만
     * 같은 일수 중 몇 번째로 달성했는지.
     */
    public record DayGroupRow(
            int days,
            int rank,
            String nickname,
            String profileImageUrl,
            int othersCount,
            LocalDate achievedDate,
            boolean me,
            Integer myOrder) {}

    public record GoalExample(int percent, long value) {}
}
