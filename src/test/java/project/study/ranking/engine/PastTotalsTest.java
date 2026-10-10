package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.service.RankingSource;

/** 주간 순공을 since 시점으로 되돌린다 — 끝난 조각은 전부, 걸친 조각은 시간 비율 (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST. */
class PastTotalsTest extends RankingIntegrationTestBase {

    private static final RankingBoard WEEKLY_FOCUS = new RankingBoard(FOCUS_TIME, WEEKLY, null);

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private List<PastEntry> pastAt(Instant since) {
        return calculator
                .pastTotals(RankingCalendar.window(WEEKLY_FOCUS, NOW, 0), since, source.livePieces(NOW))
                .stream()
                .sorted(Comparator.comparing(PastEntry::nickname))
                .toList();
    }

    @Test
    void 끝난_조각은_전부_걸친_조각은_시간_비율로_되돌리고_지난주는_뺀다() {
        long a = user("a");
        session(a, kst(10, 2, 9, 0), 60, 3000); // 지난주 — 빠진다
        session(a, kst(10, 6, 9, 0), 60, 3000); // since 전에 끝남 — 전부
        session(a, kst(10, 9, 9, 0), 120, 6000); // since(10:00)에 걸침 — 절반
        long b = user("b");
        session(b, kst(10, 9, 13, 0), 60, 2400); // since 뒤에만 — 0
        Instant since = kst(10, 9, 10, 0);

        assertThat(pastAt(since))
                .extracting(PastEntry::nickname, PastEntry::valueAt, PastEntry::achievedAt, PastEntry::studiedFrom)
                .containsExactly(tuple("a", 6000L, since, since), tuple("b", 0L, null, kst(10, 9, 13, 0)));
    }

    @Test
    void 진행_중_draft도_같은_규칙으로_되돌리고_탈퇴자는_빠진다() {
        long c = user("c");
        draft(c, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]"); // 마지막 수신 2분 전 — 집중 중 아님(연장 없음)
        long gone = user("gone");
        draft(gone, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]");
        withdraw(gone);

        assertThat(pastAt(kst(10, 10, 14, 0)))
                .extracting(PastEntry::nickname, PastEntry::valueAt, PastEntry::studiedFrom)
                .containsExactly(tuple("c", 3600L, kst(10, 10, 14, 0)));
    }

    @Test
    void since에_소수_초가_있어도_밀리초_비율로_한_번만_내린다() {
        long d = user("d");
        Instant start = kst(10, 10, 13, 0);
        draft(d, start, start.plusSeconds(200), 199, "[]"); // 200초 중 199초 — 99.9초 시점은 99.4, 초로 먼저 내리면 98이 된다

        assertThat(pastAt(start.plusMillis(99_900)))
                .extracting(PastEntry::nickname, PastEntry::valueAt)
                .containsExactly(tuple("d", 99L));
    }
}
