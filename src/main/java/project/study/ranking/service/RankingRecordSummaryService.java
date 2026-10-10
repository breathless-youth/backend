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
import project.study.ranking.close.Medals;
import project.study.ranking.dto.MedalCounts;
import project.study.ranking.dto.RankingRecordItem;
import project.study.ranking.dto.RankingRecordSummaryResponse;
import project.study.ranking.dto.RankingRecordSummaryResponse.Best;
import project.study.ranking.dto.RankingRecordSummaryResponse.Closest;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.StandingsProvider;
import project.study.ranking.repository.RankingRecordQueries;
import project.study.studysession.entity.TimeSlot;

/**
 * 메달 버튼·시트 요약 (BY-828). 트랜잭션을 걸지 않는다 — 가까운 판 계산이 순위표 계산(자체 REPEATABLE_READ 트랜잭션)을 부른다
 * (ADR-0028 결정 8). 기록 조회는 각각 자동 커밋으로 읽는다.
 */
@Service
@RequiredArgsConstructor
public class RankingRecordSummaryService {

    static final int RECENT_SIZE = 6;

    private final RankingRecordQueries queries;
    private final StandingsProvider standingsProvider;
    private final Clock clock;

    /** 기록이 있으면 순위별 개수와 최근 6개, 없으면 역대 최고 순위와 메달에 가장 가까운 진행 중 시간 판. */
    public RankingRecordSummaryResponse summary(long userId) {
        MedalCounts counts = queries.counts(userId);
        if (counts.total() > 0) {
            List<RankingRecordItem> recent = queries.page(userId, null, null, null, RECENT_SIZE).stream()
                    .map(RecordItems::of)
                    .toList();
            return new RankingRecordSummaryResponse(
                    counts.total(), counts.first(), counts.second(), counts.third(), recent, null, null);
        }
        return new RankingRecordSummaryResponse(0, 0, 0, 0, List.of(), best(userId), closest(userId, clock.instant()));
    }

    private Best best(long userId) {
        return queries.best(userId)
                .map(row -> {
                    RankingBoard board = RankingBoard.fromKey(row.boardKey());
                    return new Best(row.rank(), board.type(), board.period(), board.slot(), row.periodStart());
                })
                .orElse(null);
    }

    /**
     * 순공 일·주·월과 시간대 일·주 × 5구간의 지금 기간 중 메달까지 남은 양이 가장 작은 판 — 같으면 closable() 순서상 앞 판. 지금부터
     * 더 쌓을 수 있는 판만 본다: 오늘 이미 지난 구간의 일간판은 메달권(gap 0)일 때만 후보다.
     */
    private Closest closest(long userId, Instant now) {
        Closest closest = null;
        for (RankingBoard board : RankingBoard.closable()) {
            if (board.type() == RankingBoardType.FOCUS_RATE) {
                continue;
            }
            Window window = RankingCalendar.window(board, now, 0);
            Placement placement =
                    standingsProvider.view(board, window, userId, now).placement();
            long gap = medalGap(placement);
            if (gap > 0 && !canStillAccrue(board, window, now)) {
                continue;
            }
            if (closest == null || gap < closest.gap()) {
                closest = new Closest(board.type(), board.period(), board.slot(), gap);
            }
        }
        return closest;
    }

    /**
     * 시간대 판에 지금부터 마감 전까지 그 구간 시간이 남아 있는가 — 오늘 이미 지난 구간의 일간판은 더 쌓을 수 없다. 순공 판은 늘 참이다.
     */
    static boolean canStillAccrue(RankingBoard board, Window window, Instant now) {
        if (board.type() != RankingBoardType.TIME_SLOT) {
            return true;
        }
        for (Instant t = now; t.isBefore(window.closesAt()); t = TimeSlot.nextBoundary(t)) {
            LocalDate slotDate = TimeSlot.slotDateOf(t);
            if (TimeSlot.at(t) == board.slot()
                    && !slotDate.isBefore(window.start())
                    && !slotDate.isAfter(window.end())) {
                return true;
            }
        }
        return false;
    }

    /** max(0, 3위 값 − 내 값, 1800 − 내 값) — 참가 전이면 내 값 0, 3위가 없으면 3위 값 0. */
    static long medalGap(Placement placement) {
        double mine = placement.present() ? placement.me().value() : 0;
        double third = placement.size() >= Medals.PODIUM
                ? placement.merged().get(Medals.PODIUM - 1).value()
                : 0;
        return Math.round(Math.max(0, Math.max(third - mine, Medals.MIN_TIME_VALUE - mine)));
    }
}
