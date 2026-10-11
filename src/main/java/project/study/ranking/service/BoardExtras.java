package project.study.ranking.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse.DayGroupRow;
import project.study.ranking.dto.RankingBoardResponse.GoalExample;
import project.study.ranking.dto.RankingBoardResponse.NextTier;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.dto.RankingBoardResponse.StreakCard;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.DayGroup;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.RateTotals;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;
import project.study.ranking.engine.Tiers;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.service.RankingSource;

/** 판별 추가 필드 (BY-828) — 해당하지 않는 판이면 null을 준다. */
@Component
@RequiredArgsConstructor
class BoardExtras {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StandingsCalculator calculator;
    private final StandingsProvider provider;
    private final RankingSource source;

    /** 집중률 참가 진행도 — 기간에 세션이 없으면 null, 미달이면 지금 집중률로 참가할 때의 예상 순위를 붙인다. */
    RateEligibility eligibility(RankingBoard board, Window window, BoardView view, long userId, Instant now) {
        if (board.type() != RankingBoardType.FOCUS_RATE) {
            return null;
        }
        return calculator
                .rateTotals(board, window, view.live().asOf(), view.live().pieces(), userId)
                .map(totals -> toEligibility(board.period(), view.standings(), totals, userId, now))
                .orElse(null);
    }

    /** 누적 시간·누적 일수의 다음 상위 % 구간까지 남은 양 — 이미 1% 안이거나 닿을 구간이 없으면 null. */
    NextTier nextTier(RankingBoard board, Placement placement, long userId, Instant now) {
        RankingBoardType type = board.type();
        boolean cumulative = type == RankingBoardType.TOTAL_TIME || type == RankingBoardType.TOTAL_DAYS;
        if (!cumulative || !placement.present()) {
            return null;
        }
        OptionalInt tier = Tiers.next(placement.topPercent(), placement.size());
        if (tier.isEmpty()) {
            return null;
        }
        RankingEntry cutoff = placement.merged().get(Tiers.cutoffRank(tier.getAsInt(), placement.size()) - 1);
        // 공동 순위면 컷의 값과 같아지기만 해도 그 순위라 넘을 필요가 없다
        long remaining = (long) (cutoff.value() - placement.me().value()) + (placement.tied() ? 0 : 1);
        return new NextTier(tier.getAsInt(), remaining, etaDays(type, userId, remaining, today(now)));
    }

    /** 연속 공부 일수 카드 — 최고 기록과 지금 연속. 지금이 최고 기록이면 내일도 하면 받을 공동 순위를 붙인다. */
    StreakCard streakCard(RankingBoard board, Standings standings, long userId, Instant now) {
        if (board.type() != RankingBoardType.MAX_STREAK) {
            return null;
        }
        return source.maxStreaks(today(now), userId).stream()
                .findFirst()
                .map(row -> {
                    int current = source.currentStreak(userId);
                    boolean currentIsBest = current > 0 && current == row.days();
                    Integer nextRank = currentIsBest
                            ? standings
                                    .place(
                                            userId,
                                            RankingEntry.of(userId, null, row.days() + 1, now.plus(Duration.ofDays(1))))
                                    .withTies(true)
                                    .myRank()
                            : null;
                    return new StreakCard(row.days(), row.startDate(), row.endDate(), current, currentIsBest, nextRank);
                })
                .orElse(null);
    }

    /** 일수 판의 같은 일수 묶음 앞 2 · 내 묶음 · 뒤 2 — 내 묶음은 내 이름과 같은 일수 중 몇 번째인지. */
    List<DayGroupRow> aroundGroups(RankingBoard board, Placement placement) {
        if (!board.type().tiedRanks()) {
            return null;
        }
        if (!placement.present()) {
            return List.of();
        }
        List<DayGroup> groups = DayGroup.of(placement.merged());
        int start = Placement.windowStart(groups.size(), DayGroup.indexContaining(groups, placement.myIndex()));
        return groups.subList(start, Math.min(groups.size(), start + Placement.WINDOW)).stream()
                .map(group -> toRow(group, placement))
                .toList();
    }

    /** 순공 주간에 이번 주 기록이 없을 때 — 지난주 최종 분포에서 구간마다 필요한 순공(분 단위 올림). */
    List<GoalExample> goalExamples(RankingBoard board, int offset, Placement placement, Instant now) {
        if (board.type() != RankingBoardType.FOCUS_TIME
                || board.period() != RankingPeriod.WEEKLY
                || offset != 0
                || placement.present()) {
            return null;
        }
        Window lastWeek = RankingCalendar.window(board, now, -1);
        List<RankingEntry> last =
                provider.standings(board, lastWeek, provider.live(), now).entries();
        List<GoalExample> examples = new ArrayList<>();
        for (int percent : Tiers.PERCENTS) {
            int cutoff = Tiers.cutoffRank(percent, last.size());
            if (cutoff >= 1) {
                examples.add(new GoalExample(
                        percent, ceilToMinute((long) last.get(cutoff - 1).value() + 1)));
            }
        }
        return examples;
    }

    private static RateEligibility toEligibility(
            RankingPeriod period, Standings standings, RateTotals totals, long userId, Instant now) {
        long required = StandingsCalculator.requiredRateFocusSec(period);
        boolean eligible = totals.focusSec() >= required;
        double rate = BoardValues.round1(totals.rate());
        if (eligible) {
            return new RateEligibility(true, totals.focusSec(), required, rate, null, null);
        }
        // 나를 끼운 배치라 상위 %의 분모는 나를 포함한 참가자 수다 — 참가했을 때의 me.topPercent와 같은 식
        Placement expected = standings.place(
                userId,
                new RankingEntry(userId, null, totals.rate(), now, false, totals.focusSec(), totals.studySec()));
        return new RateEligibility(false, totals.focusSec(), required, rate, expected.myRank(), expected.topPercent());
    }

    private Integer etaDays(RankingBoardType type, long userId, long remaining, LocalDate today) {
        LocalDate from = today.minusDays(6);
        double perDay = (type == RankingBoardType.TOTAL_TIME
                        ? source.periodTotals(from, today, userId).stream()
                                .mapToLong(RankingTotalRow::focusSec)
                                .sum()
                        : source.studiedDays(userId, from, today))
                / 7.0;
        return perDay <= 0 ? null : (int) Math.ceil(remaining / perDay);
    }

    /** 일수 판의 시상대 — 일수 많은 순 묶음 3개. 내가 없어도 준다. */
    List<DayGroupRow> podiumGroups(RankingBoard board, Placement placement) {
        if (!board.type().tiedRanks()) {
            return null;
        }
        List<DayGroup> groups = DayGroup.of(placement.merged());
        return groups.subList(0, Math.min(3, groups.size())).stream()
                .map(group -> toRow(group, placement))
                .toList();
    }

    /** 묶음 순위는 공동 순위(첫 자리 + 1). 대표는 내가 있으면 나, 없으면 먼저 달성한 사람. */
    private static DayGroupRow toRow(DayGroup group, Placement placement) {
        if (!group.contains(placement.myIndex())) {
            RankingEntry head = placement.merged().get(group.startIndex());
            return new DayGroupRow(
                    group.days(),
                    group.startIndex() + 1,
                    head.nickname(),
                    group.count() - 1,
                    head.achievedDate(),
                    false,
                    null);
        }
        RankingEntry me = placement.me();
        return new DayGroupRow(
                group.days(),
                group.startIndex() + 1,
                me.nickname(),
                group.count() - 1,
                me.achievedDate(),
                true,
                placement.myIndex() - group.startIndex() + 1);
    }

    private static LocalDate today(Instant now) {
        return now.atZone(KST).toLocalDate();
    }

    private static long ceilToMinute(long seconds) {
        return (seconds + 59) / 60 * 60;
    }
}
