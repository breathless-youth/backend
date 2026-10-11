package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.close.RankingCloseWriter;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

/** 마감 표시·메달·개인 최고를 한 트랜잭션에 쓰고, 이미 확정된 판에는 아무것도 쓰지 않는다 (BY-828). */
class RankingCloseWriterTest extends RankingIntegrationTestBase {

    private static final RankingBoard DAILY = new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.DAILY, null);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloseWriter writer;

    private static Window friday() {
        return new Window(FRI, FRI, kst(10, 10, 0, 0));
    }

    @Test
    void 마감_표시_메달_개인_최고를_함께_쓰고_두_번째_쓰기는_아무것도_남기지_않는다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");
        Standings standings = Standings.of(
                List.of(
                        RankingEntry.of(a, "a", 3000, kst(10, 9, 10, 0)),
                        RankingEntry.of(b, "b", 1700, kst(10, 9, 14, 0)),
                        RankingEntry.of(c, "c", 2400, kst(10, 9, 12, 0))),
                kst(10, 10, 0, 1));

        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 1), standings)).isTrue();
        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 2), standings)).isFalse();

        assertThat(jdbc.queryForList("SELECT user_id, rank, value FROM ranking_record ORDER BY rank"))
                .extracting(
                        row -> row.get("user_id"),
                        row -> row.get("rank"),
                        row -> ((BigDecimal) row.get("value")).toPlainString())
                .containsExactly(tuple(a, 1, "3000.0"), tuple(c, 2, "2400.0"));
        assertThat(jdbc.queryForObject("SELECT closed_at FROM ranking_close", OffsetDateTime.class)
                        .toInstant())
                .isEqualTo(kst(10, 10, 0, 1));
        assertThat(jdbc.queryForList("SELECT user_id, rank FROM ranking_best ORDER BY rank"))
                .extracting(row -> row.get("user_id"), row -> row.get("rank"))
                .containsExactly(tuple(a, 1), tuple(c, 2), tuple(b, 3));
    }

    @Test
    void 참가자가_없어도_마감을_표시한다() {
        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 1), Standings.of(List.of(), kst(10, 10, 0, 1))))
                .isTrue();

        assertThat(jdbc.queryForObject("SELECT skipped FROM ranking_close", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ranking_record", Integer.class))
                .isZero();
    }
}
