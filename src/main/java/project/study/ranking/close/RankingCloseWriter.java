package project.study.ranking.close;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;
import project.study.ranking.repository.RankingCloseRepository;

/**
 * 판 하나의 마감을 한 트랜잭션에 쓴다 (BY-828, ADR-0029) — 마감 표시, 메달, 참가자 전원의 개인 최고. 순위표 계산은 이 트랜잭션
 * 밖에서 끝내고 넘긴다 — 계산이 자체 REPEATABLE_READ 트랜잭션을 열어야 해서 쓰기 트랜잭션 안에서 부를 수 없다(ADR-0028 결정 8).
 */
@Component
@RequiredArgsConstructor
public class RankingCloseWriter {

    private final RankingCloseRepository closes;

    /** 다른 태스크가 같은 판·기간을 먼저 확정했으면 아무것도 쓰지 않고 false — 마감 표시의 PK가 하나만 통과시킨다. */
    @Transactional
    public boolean write(RankingBoard board, Window window, Instant closedAt, Standings standings) {
        if (!closes.insertClose(board.key(), window.start(), window.closesAt(), closedAt, false)) {
            return false;
        }
        for (RankedEntry medal : Medals.of(board, standings)) {
            closes.insertRecord(
                    medal.entry().userId(),
                    board.key(),
                    window.start(),
                    window.closesAt(),
                    medal.rank(),
                    Medals.recordValue(medal.entry().value()));
        }
        closes.upsertBest(
                board.key(),
                window.start(),
                standings.entries().stream().map(RankingEntry::userId).toList());
        return true;
    }
}
