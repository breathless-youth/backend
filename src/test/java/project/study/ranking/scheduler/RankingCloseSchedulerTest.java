package project.study.ranking.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.close.RankingCloser;

class RankingCloseSchedulerTest {

    @Test
    void 지금_시각으로_마감을_돌리고_예외는_삼킨다() {
        Instant now = Instant.parse("2026-10-09T15:01:05Z");
        List<Instant> calls = new ArrayList<>();
        RankingCloser closer = new RankingCloser(null, null, null, null) {
            @Override
            public void closeDue(Instant at) {
                calls.add(at);
                throw new IllegalStateException("마감 실패");
            }
        };
        RankingCloseScheduler scheduler = new RankingCloseScheduler(closer, Clock.fixed(now, ZoneOffset.UTC));

        assertThatCode(scheduler::closeDueBoards).doesNotThrowAnyException();
        assertThat(calls).containsExactly(now);
    }
}
