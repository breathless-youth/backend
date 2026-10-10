package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.service.RankingSource;

/** 확정 합계와 남은 draft id는 한 스냅샷에서 읽어야 한다 — 그 사이 확정 커밋이 끼면 한쪽만 보여 이중 집계된다 (BY-828). */
class StandingsCalculatorIsolationTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @MockitoSpyBean
    private RankingSource source;

    @Test
    void 남은_draft_id는_반복_읽기_트랜잭션_안에서_읽는다() {
        long a = user("a");
        draft(a, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        AtomicReference<String> isolation = new AtomicReference<>();
        doAnswer(invocation -> {
                    isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                    return invocation.callRealMethod();
                })
                .when(source)
                .openDraftIds();
        RankingBoard board = new RankingBoard(FOCUS_TIME, WEEKLY, null);

        calculator.compute(board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), null);

        assertThat(isolation).hasValue("repeatable read");
    }
}
