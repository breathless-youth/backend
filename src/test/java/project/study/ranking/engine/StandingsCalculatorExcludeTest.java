package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_DAYS;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/** 세션 뒤 오른 랭킹의 "이번 세션 전" 값 — 그 제출의 조각만 빼고 같은 엔진으로 계산한다 (BY-828 §7.3). */
class StandingsCalculatorExcludeTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private double valueWithout(RankingBoard board, long userId, Instant excluded) {
        return calculator
                .compute(board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), userId, excluded)
                .stream()
                .findFirst()
                .map(RankingEntry::value)
                .orElse(0.0);
    }

    @Test
    void 제출을_빼면_그_제출의_조각만_빠진다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 60, 3000); // 금 오전
        Instant last = kst(10, 10, 9, 0);
        session(me, last, 60, 2000); // 토 오전 — 뺄 제출

        assertThat(valueWithout(new RankingBoard(FOCUS_TIME, WEEKLY, null), me, last))
                .isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.MORNING), me, last))
                .isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TOTAL_TIME, null, null), me, last))
                .isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TOTAL_DAYS, null, null), me, last))
                .isEqualTo(1);
        assertThat(valueWithout(new RankingBoard(MAX_STREAK, null, null), me, last))
                .isEqualTo(1);
        assertThat(valueWithout(new RankingBoard(MAX_STREAK, null, null), me, null))
                .isEqualTo(2);
        assertThat(valueWithout(new RankingBoard(FOCUS_TIME, DAILY, null), me, last))
                .isZero();
    }

    @Test
    void 제출이_지난_구간을_주고_내_제출이_아니면_비어_있다() {
        long me = user("me");
        long other = user("other");
        Instant start = kst(10, 10, 11, 30);
        session(me, start, 60, 3000); // 11:30–12:30 — 오전·오후

        assertThat(source.submissionSlots(me, start)).contains(EnumSet.of(TimeSlot.MORNING, TimeSlot.AFTERNOON));
        assertThat(source.submissionSlots(other, start)).isEmpty();
        assertThat(source.submissionSlots(me, start.plusSeconds(1))).isEmpty();
    }
}
