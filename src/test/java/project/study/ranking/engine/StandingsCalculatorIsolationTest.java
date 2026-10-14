package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.service.RankingSource;

/** 확정 합계와 남은 draft id는 한 스냅샷에서 읽어야 한다 — 그 사이 확정 커밋이 끼면 한쪽만 보여 이중 집계된다 (BY-828). */
class StandingsCalculatorIsolationTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private RankingSource source;

    @Test
    void 남은_draft_id는_반복_읽기_트랜잭션_안에서_읽는다() {
        long a = user("a");
        draft(a, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        AtomicReference<String> isolation = new AtomicReference<>();
        AtomicReference<Integer> springLevel = new AtomicReference<>();
        doAnswer(invocation -> {
                    isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                    springLevel.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                    return invocation.callRealMethod();
                })
                .when(source)
                .unfinalizedDraftIds();
        RankingBoard board = new RankingBoard(FOCUS_TIME, WEEKLY, null);

        calculator.compute(board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), null);

        assertThat(isolation).hasValue("repeatable read");
        assertThat(springLevel).hasValue(Connection.TRANSACTION_REPEATABLE_READ);
    }

    private void inOuterTransaction(int isolationLevel, Runnable body) {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(isolationLevel);
        outer.executeWithoutResult(status -> body.run());
    }

    @Test
    void 기본_격리_수준의_바깥_트랜잭션_안에서_부르면_예외다() {
        RankingBoard time = new RankingBoard(FOCUS_TIME, WEEKLY, null);
        RankingBoard rate = new RankingBoard(FOCUS_RATE, WEEKLY, null);

        assertThatThrownBy(() -> inOuterTransaction(
                        TransactionDefinition.ISOLATION_DEFAULT,
                        () -> calculator.compute(time, RankingCalendar.window(time, NOW, 0), NOW, List.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPEATABLE_READ");
        assertThatThrownBy(() -> inOuterTransaction(
                        TransactionDefinition.ISOLATION_DEFAULT,
                        () -> calculator.rateTotals(rate, RankingCalendar.window(rate, NOW, 0), NOW, List.of(), 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPEATABLE_READ");
    }

    @Test
    void 바깥_트랜잭션이_이미_반복_읽기면_그대로_쓴다() {
        RankingBoard time = new RankingBoard(FOCUS_TIME, WEEKLY, null);

        assertThatCode(() -> inOuterTransaction(
                        TransactionDefinition.ISOLATION_REPEATABLE_READ,
                        () -> calculator.compute(time, RankingCalendar.window(time, NOW, 0), NOW, List.of(), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void 한_사용자만_계산할_때_그_사용자의_조각이_없으면_남은_draft_id를_읽지_않는다() {
        long a = user("a");
        long b = user("b");
        draft(a, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        List<LivePiece> live = source.livePieces(NOW);
        RankingBoard board = new RankingBoard(FOCUS_TIME, WEEKLY, null);
        var window = RankingCalendar.window(board, NOW, 0);

        assertThat(live).hasSize(1);
        assertThat(calculator.compute(board, window, NOW, live, b)).isEmpty();
        verify(source, never()).unfinalizedDraftIds();

        assertThat(calculator.compute(board, window, NOW, live, a))
                .extracting(RankingEntry::value)
                .containsExactly(600.0);
        verify(source, times(1)).unfinalizedDraftIds();
    }
}
