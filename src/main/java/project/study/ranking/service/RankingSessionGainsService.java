package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.NotFoundException;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingSessionGainsResponse;
import project.study.ranking.dto.RankingSessionGainsResponse.BoardGain;
import project.study.ranking.dto.RankingSessionGainsResponse.Gain;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/**
 * 세션 뒤 오른 랭킹 (BY-828 §7.3) — 판마다 "이번 제출을 뺀 나"와 "지금의 나"를 지금의 같은 다른 사람들 사이에서 매긴다. 이번 세션 전에
 * 순위가 없던 판은 넣지 않는다. 트랜잭션을 걸지 않는다 — 순위표 계산이 자체 REPEATABLE_READ 트랜잭션을 연다(ADR-0028 결정 8).
 */
@Service
@RequiredArgsConstructor
public class RankingSessionGainsService {

    private static final RankingBoard WEEKLY_FOCUS =
            new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.WEEKLY, null);

    private final StandingsProvider provider;
    private final StandingsCalculator calculator;
    private final RankingSource source;
    private final Clock clock;

    public RankingSessionGainsResponse gains(long userId, Instant submissionStartedAt) {
        Set<TimeSlot> slots = source.submissionSlots(userId, submissionStartedAt)
                .orElseThrow(() -> new NotFoundException("이 시각에 시작한 내 세션이 없습니다"));
        Instant now = clock.instant();
        Gain weekly = null;
        List<BoardGain> others = new ArrayList<>();
        for (RankingBoard board : targets(slots)) {
            Gain gain = gain(board, userId, submissionStartedAt, now);
            if (gain == null) {
                continue;
            }
            if (board.equals(WEEKLY_FOCUS)) {
                weekly = gain;
            } else {
                others.add(new BoardGain(
                        board.type(), board.period(), board.slot(), gain.before(), gain.after(), gain.delta()));
            }
        }
        return new RankingSessionGainsResponse(weekly, others);
    }

    /** 명세 칩 순서 — 순공 일·주·월, 집중률 주·월, 시간대 일·주(이번 제출이 지난 구간만), 누적 시간·누적 일수·연속 일수. */
    static List<RankingBoard> targets(Set<TimeSlot> slots) {
        List<RankingBoard> boards = new ArrayList<>();
        for (RankingPeriod period : RankingPeriod.values()) {
            boards.add(new RankingBoard(RankingBoardType.FOCUS_TIME, period, null));
        }
        boards.add(new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.WEEKLY, null));
        boards.add(new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.MONTHLY, null));
        for (RankingPeriod period : List.of(RankingPeriod.DAILY, RankingPeriod.WEEKLY)) {
            for (TimeSlot slot : TimeSlot.values()) {
                if (slots.contains(slot)) {
                    boards.add(new RankingBoard(RankingBoardType.TIME_SLOT, period, slot));
                }
            }
        }
        boards.add(new RankingBoard(RankingBoardType.TOTAL_TIME, null, null));
        boards.add(new RankingBoard(RankingBoardType.TOTAL_DAYS, null, null));
        boards.add(new RankingBoard(RankingBoardType.MAX_STREAK, null, null));
        return boards;
    }

    /** 지금 순위가 있고, 이번 제출을 뺀 값으로도 순위가 있으며, 지금이 더 높을 때만. */
    private Gain gain(RankingBoard board, long userId, Instant submissionStartedAt, Instant now) {
        Window window = RankingCalendar.window(board, now, 0);
        BoardView view = provider.view(board, window, userId, now);
        if (!view.placement().present()) {
            return null;
        }
        Instant asOf = view.live().asOf();
        RankingEntry before =
                calculator.compute(board, window, asOf, view.live().pieces(), userId, submissionStartedAt).stream()
                        .findFirst()
                        .orElse(null);
        if (before == null) {
            return null;
        }
        int beforeRank =
                view.standings().place(userId, before.advancedTo(asOf, now)).myRank();
        int afterRank = view.placement().myRank();
        return afterRank < beforeRank ? new Gain(beforeRank, afterRank, beforeRank - afterRank) : null;
    }
}
