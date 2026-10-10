package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.BoardMe;
import project.study.ranking.dto.RankingBoardResponse.BoardNeighbor;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.StandingsProvider;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판 조회 (BY-828, ADR-0028) — 캐시된 순위표에 새로 읽은 내 줄을 끼워 응답을 조립한다. */
@Service
@RequiredArgsConstructor
public class RankingBoardService {

    private final StandingsProvider provider;
    private final BoardExtras extras;
    private final Clock clock;

    public RankingBoardResponse board(
            long userId, RankingBoardType type, RankingPeriod period, TimeSlot slot, int offset) {
        Instant now = clock.instant();
        RankingBoard board = RankingBoard.of(type, period, resolveSlot(type, slot, now), offset);
        Window window = RankingCalendar.window(board, now, offset);
        BoardView view = provider.view(board, window, userId, now);
        Placement placement = view.placement();
        RankingEntry me = placement.me();
        RateEligibility eligibility = extras.eligibility(board, window, view, userId, now);
        return new RankingBoardResponse(
                type,
                board.period(),
                board.slot(),
                periodStart(board, window),
                window.closesAt(),
                now,
                entries(placement.podium(), userId, type),
                myRank(placement, type),
                around(placement, eligibility, userId, type),
                neighbor(placement.above(), me, type, true),
                neighbor(placement.below(), me, type, false),
                startNowRank(placement, type, offset),
                eligibility,
                extras.nextTier(board, placement, userId, now),
                extras.streakCard(board, view.standings(), userId, now),
                extras.streakGroups(board, placement),
                extras.goalExamples(board, offset, placement, now));
    }

    /** 총공부 시간이 같을 때 바로 위 집중률을 넘으려면 더 해야 하는 순공(초) — 정수 비교로 부동소수 오차를 피한다. */
    static long catchUpFocusSec(RankingEntry above, RankingEntry me) {
        long needed = Math.floorDiv(above.focusSec() * me.studySec(), above.studySec()) + 1;
        return Math.max(1, needed - me.focusSec());
    }

    private static TimeSlot resolveSlot(RankingBoardType type, TimeSlot slot, Instant now) {
        return type == RankingBoardType.TIME_SLOT && slot == null ? TimeSlot.at(now) : slot;
    }

    private static LocalDate periodStart(RankingBoard board, Window window) {
        return board.type().hallOfFame() ? null : window.start();
    }

    private static BoardMe myRank(Placement placement, RankingBoardType type) {
        if (!placement.present()) {
            return null;
        }
        RankingEntry me = placement.me();
        return new BoardMe(
                placement.myRank(), BoardValues.value(type, me.value()), me.focusing(), placement.topPercent());
    }

    private static List<BoardEntry> around(
            Placement placement, RateEligibility eligibility, long userId, RankingBoardType type) {
        if (type == RankingBoardType.MAX_STREAK) {
            return List.of();
        }
        if (eligibility != null && !eligibility.eligible()) {
            return entries(placement.windowAround(eligibility.expectedRank() - 1), userId, type);
        }
        return entries(placement.around(), userId, type);
    }

    /** 지금 기간에 참가하지 않았을 때만 — 지난 기간(offset≠0)에는 "지금 시작하면"이 성립하지 않고, 집중률 판은 eligibility가 맡는다. */
    private static Integer startNowRank(Placement placement, RankingBoardType type, int offset) {
        boolean applicable = offset == 0 && type != RankingBoardType.FOCUS_RATE && !placement.present();
        return applicable ? placement.size() + 1 : null;
    }

    private static List<BoardEntry> entries(List<RankedEntry> ranked, long userId, RankingBoardType type) {
        return ranked.stream()
                .map(row -> new BoardEntry(
                        row.rank(),
                        row.entry().nickname(),
                        BoardValues.value(type, row.entry().value()),
                        row.entry().focusing(),
                        row.entry().userId() == userId))
                .toList();
    }

    private static BoardNeighbor neighbor(RankingEntry other, RankingEntry me, RankingBoardType type, boolean above) {
        if (other == null || me == null) {
            return null;
        }
        Long catchUp = above && type == RankingBoardType.FOCUS_RATE ? catchUpFocusSec(other, me) : null;
        return new BoardNeighbor(
                other.nickname(), BoardValues.gap(type, other.value(), me.value()), other.focusing(), catchUp);
    }
}
