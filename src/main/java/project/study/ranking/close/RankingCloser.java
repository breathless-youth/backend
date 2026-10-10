package project.study.ranking.close;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.repository.RankingCloseRepository;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.service.RankingSource;

/**
 * 직전 기간이 마감된 판을 확정한다 (BY-828, ADR-0029). 판 하나 = 계산 한 번(트랜잭션 밖, 엔진이 자체 REPEATABLE_READ 트랜잭션을
 * 연다) + 쓰기 트랜잭션 하나. 이 클래스는 트랜잭션을 걸지 않는다 — 걸면 엔진의 격리 수준 보장이 사라져 바로 실패한다(ADR-0028 결정 8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingCloser {

    /** 마감 뒤 이만큼 기다렸다 확정한다 — 자정 뒤 첫 스냅샷(하트비트 30초 + flush 5초 + 네트워크)이 들어오는 시간. */
    public static final Duration SETTLE_DELAY = Duration.ofMinutes(1);

    /** 이보다 늦으면 확정하지 않고 건너뛴다 — 첫 배포 때 지난 기간에 메달이 소급되지 않고, 04시 보류가 영영 막히지 않게. */
    public static final Duration CATCH_UP_LIMIT = Duration.ofHours(1);

    private final StandingsCalculator calculator;
    private final RankingSource source;
    private final RankingCloseRepository closes;
    private final RankingCloseWriter writer;

    /** now 기준 직전 기간이 마감 + 1분을 지났고 아직 마감 표시가 없는 판을 확정한다. 판 하나가 실패해도 나머지는 계속한다. */
    public void closeDue(Instant now) {
        List<LivePiece> pieces = null;
        for (RankingBoard board : RankingBoard.closable()) {
            Window window = RankingCalendar.window(board, now, -1);
            if (now.isBefore(window.closesAt().plus(SETTLE_DELAY)) || closes.isClosed(board.key(), window.start())) {
                continue;
            }
            try {
                if (now.isAfter(window.closesAt().plus(CATCH_UP_LIMIT))) {
                    skip(board, window, now);
                    continue;
                }
                if (pieces == null) {
                    pieces = source.settledPieces(now);
                }
                close(board, window, now, pieces);
            } catch (RuntimeException e) {
                log.error("랭킹 마감 실패 — 다음 틱에 재시도: board={}, periodStart={}", board.key(), window.start(), e);
                Sentry.captureException(e);
            }
        }
    }

    private void close(RankingBoard board, Window window, Instant now, List<LivePiece> pieces) {
        Standings standings = Standings.of(calculator.compute(board, window, now, pieces, null), now);
        if (writer.write(board, window, now, standings)) {
            log.info(
                    "랭킹 마감 확정: board={}, periodStart={}, participants={}",
                    board.key(),
                    window.start(),
                    standings.entries().size());
        }
    }

    private void skip(RankingBoard board, Window window, Instant now) {
        if (closes.insertClose(board.key(), window.start(), window.closesAt(), now, true)) {
            log.warn("랭킹 마감을 1시간 안에 확정하지 못해 건너뜀: board={}, periodStart={}", board.key(), window.start());
            Sentry.captureMessage("랭킹 마감 건너뜀: " + board.key() + " " + window.start(), SentryLevel.WARNING);
        }
    }
}
