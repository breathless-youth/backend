package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingHomeResponse;
import project.study.ranking.dto.RankingHomeResponse.HomeCard;
import project.study.ranking.dto.RankingHomeResponse.HomeNeighbor;
import project.study.ranking.dto.RankingHomeResponse.Overtaken;
import project.study.ranking.dto.RankingHomeResponse.Overtaker;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.PastEntry;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;

/**
 * 홈 랭킹 (BY-828 §7.3) — 이번 주 순공 판의 한 줄 카드와, since 뒤로 나를 추월한 사람. 트랜잭션을 걸지 않는다 — 순위표 계산이
 * 자체 REPEATABLE_READ 트랜잭션을 연다(ADR-0028 결정 8).
 */
@Service
@RequiredArgsConstructor
public class RankingHomeService {

    static final int NEAREST = 2;

    private static final RankingBoard WEEKLY_FOCUS =
            new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.WEEKLY, null);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StandingsProvider provider;
    private final StandingsCalculator calculator;
    private final Clock clock;

    public RankingHomeResponse home(long userId, Instant since) {
        Instant now = clock.instant();
        Window window = RankingCalendar.window(WEEKLY_FOCUS, now, 0);
        BoardView view = provider.view(WEEKLY_FOCUS, window, userId, now);
        if (!view.placement().present()) {
            return new RankingHomeResponse(null, null);
        }
        return new RankingHomeResponse(card(view.placement()), overtaken(view, window, userId, since, now));
    }

    private static HomeCard card(Placement placement) {
        RankingEntry me = placement.me();
        RankingEntry above = placement.above();
        return new HomeCard(
                placement.myRank(),
                Math.round(me.value()),
                above == null ? null : new HomeNeighbor(above.nickname(), gap(above, me)));
    }

    /** since 시점 순위표를 되돌려 지금과 비교한다 — 그때 내 앞이 아니던(미참가 포함) 사람 중 지금 내 앞인 사람이 추월한 사람이다. */
    private Overtaken overtaken(BoardView view, Window window, long userId, Instant since, Instant now) {
        Instant weekStart = window.start().atStartOfDay(KST).toInstant();
        if (since == null || since.isBefore(weekStart) || since.isAfter(now)) {
            return null;
        }
        if (view.placement().myIndex() == 0) {
            return null; // 지금 1위면 내려갈 수 없다 — 가장 비싼 주간 전체 집계를 건너뛴다
        }
        Map<Long, PastEntry> past = calculator
                .pastTotals(window, since, view.live().pieces())
                .stream()
                .collect(Collectors.toMap(PastEntry::userId, Function.identity()));
        List<RankingEntry> then = Standings.of(
                        past.values().stream()
                                .filter(entry -> entry.valueAt() > 0)
                                .map(PastEntry::toEntry)
                                .toList(),
                        since)
                .entries();
        int fromIndex = indexOf(then, userId);
        Placement placement = view.placement();
        if (fromIndex < 0 || placement.myIndex() <= fromIndex) {
            return null;
        }
        Set<Long> aheadThen =
                then.subList(0, fromIndex).stream().map(RankingEntry::userId).collect(Collectors.toSet());
        List<RankingEntry> overtakers = placement.merged().subList(0, placement.myIndex()).stream()
                .filter(entry -> !aheadThen.contains(entry.userId()))
                .toList();
        return new Overtaken(
                fromIndex + 1, placement.myRank(), overtakers.size(), nearest(overtakers, placement.me(), past, since));
    }

    /** 추월한 사람 중 지금 나와 가장 가까운 순 — 바로 위부터 위로 올라간다. */
    private static List<Overtaker> nearest(
            List<RankingEntry> overtakers, RankingEntry me, Map<Long, PastEntry> past, Instant since) {
        List<Overtaker> rows = new ArrayList<>(NEAREST);
        for (int i = overtakers.size() - 1; i >= 0 && rows.size() < NEAREST; i--) {
            RankingEntry other = overtakers.get(i);
            PastEntry then = past.get(other.userId());
            // 캐시된 지금 순위표(10초)에는 있는데 새로 읽은 지난 값에 없는 사람(그 사이 탈퇴·draft 폐기) — since부터 전부 쌓은 것으로 본다
            long valueThen = then == null ? 0 : then.valueAt();
            Instant studiedFrom = then == null || then.studiedFrom() == null ? since : then.studiedFrom();
            rows.add(new Overtaker(
                    other.nickname(), gap(other, me), studiedFrom, Math.max(0, Math.round(other.value()) - valueThen)));
        }
        return rows;
    }

    private static int indexOf(List<RankingEntry> entries, long userId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).userId() == userId) {
                return i;
            }
        }
        return -1;
    }

    private static long gap(RankingEntry ahead, RankingEntry me) {
        return Math.round(ahead.value() - me.value());
    }
}
