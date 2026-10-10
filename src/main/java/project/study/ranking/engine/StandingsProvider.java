package project.study.ranking.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar.Window;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.service.RankingSource;

/**
 * 캐시된 순위표와 새로 읽은 내 줄을 묶는다 (BY-828, ADR-0028). 순위표는 판·기간 단위로 캐시하고, 요청 시각까지 집중 중인 줄을
 * 올린 뒤, 캐시와 따로 매번 계산한 내 줄로 순위표의 내 옛 줄을 대신한다.
 */
@Component
@RequiredArgsConstructor
public class StandingsProvider {

    /** 진행 중 기간 판과 진행 중 조각 — FE 폴링(15초)보다 짧게. */
    public static final Duration CURRENT_TTL = Duration.ofSeconds(10);

    /** 명예의 전당·지난 기간 — 실시간이 아니다. */
    public static final Duration SLOW_TTL = Duration.ofSeconds(60);

    private static final String LIVE_KEY = "live";

    private final StandingsCalculator calculator;
    private final StandingsCache cache;
    private final RankingSource source;
    private final Clock clock;

    /**
     * 마감된 기간(지난 기간)은 마감 시각까지만 올리고 집중 중 표시를 끈다 — 진행 중 조각 캐시가 마감 전 스냅샷이면 그 조각이
     * 마지막 조각이고 집중 중이라, 요청 시각까지 올리면 최종 판이 마감 뒤에도 오르고 순위가 틀어진다.
     */
    public BoardView view(RankingBoard board, Window window, long userId, Instant now) {
        LiveSnapshot live = live();
        boolean closed = closed(window, now);
        Standings cached = standings(board, window, live, now);
        Standings standings = closed ? cached.settledAt(window.closesAt()) : cached.advancedTo(now);
        RankingEntry me = calculator.compute(board, window, live.asOf(), piecesFor(board, live), userId).stream()
                .findFirst()
                .map(entry ->
                        closed ? entry.settledAt(live.asOf(), window.closesAt()) : entry.advancedTo(live.asOf(), now))
                .orElse(null);
        return new BoardView(standings, standings.place(me), live);
    }

    public Standings standings(RankingBoard board, Window window, LiveSnapshot live, Instant now) {
        boolean slow = board.type().hallOfFame() || closed(window, now);
        return cache.get(
                new StandingsKey(board.key(), window.start()),
                slow ? SLOW_TTL : CURRENT_TTL,
                () -> Standings.of(
                        calculator.compute(board, window, live.asOf(), piecesFor(board, live), null), live.asOf()));
    }

    public LiveSnapshot live() {
        return cache.get(LIVE_KEY, CURRENT_TTL, () -> {
            Instant at = clock.instant();
            return new LiveSnapshot(source.livePieces(at), at);
        });
    }

    /** 마감 시각이 지났다 — 명예의 전당은 마감이 없다. */
    private static boolean closed(Window window, Instant now) {
        return window.closesAt() != null && !window.closesAt().isAfter(now);
    }

    private static List<LivePiece> piecesFor(RankingBoard board, LiveSnapshot live) {
        return board.type().hallOfFame() ? List.of() : live.pieces();
    }

    private record StandingsKey(String board, LocalDate periodStart) {}
}
