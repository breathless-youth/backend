package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import project.study.ranking.repository.RankingCloseRepository;

class RankingCloseRepositoryTest extends RankingIntegrationTestBase {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloseRepository repository;

    @Test
    void 같은_판_기간의_마감은_한_번만_표시한다() {
        Instant closesAt = kst(10, 10, 0, 0);

        assertThat(repository.insertClose("FOCUS_TIME:DAILY", FRI, closesAt, closesAt.plusSeconds(65), false))
                .isTrue();
        assertThat(repository.insertClose("FOCUS_TIME:DAILY", FRI, closesAt, closesAt.plusSeconds(125), false))
                .isFalse();
        assertThat(repository.isClosed("FOCUS_TIME:DAILY", FRI)).isTrue();
        assertThat(repository.isClosed("FOCUS_TIME:WEEKLY", FRI)).isFalse();
    }

    @Test
    void 마감_시각으로_세면_건너뛴_마감도_센다() {
        Instant mondayNight = kst(10, 12, 4, 0);
        repository.insertClose(
                "TIME_SLOT:DAILY:NIGHT", LocalDate.of(2026, 10, 11), mondayNight, mondayNight.plusSeconds(65), false);
        repository.insertClose(
                "TIME_SLOT:WEEKLY:NIGHT", LocalDate.of(2026, 10, 5), mondayNight, mondayNight.plusSeconds(7200), true);

        assertThat(repository.countClosedAt(List.of("TIME_SLOT:DAILY:NIGHT", "TIME_SLOT:WEEKLY:NIGHT"), mondayNight))
                .isEqualTo(2);
        assertThat(repository.countClosedAt(List.of("TIME_SLOT:DAILY:NIGHT"), mondayNight.minusSeconds(86_400)))
                .isZero();
    }

    @Test
    void 같은_판_기간의_같은_순위는_하나만_기록한다() {
        long a = user("a");
        long b = user("b");
        Instant closesAt = kst(10, 10, 0, 0);
        repository.insertRecord(a, "FOCUS_TIME:DAILY", FRI, closesAt, 1, new BigDecimal("3000.0"));

        assertThatThrownBy(() ->
                        repository.insertRecord(b, "FOCUS_TIME:DAILY", FRI, closesAt, 1, new BigDecimal("2000.0")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 개인_최고는_더_높은_순위일_때만_바꾸고_같으면_먼저_것을_둔다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");

        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 6), List.of(a, b));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 7), List.of(b, a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 8), List.of(c, b, a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 9), List.of(a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 10), List.of());

        assertThat(best(a)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-06");
        assertThat(best(b)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-07");
        assertThat(best(c)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-08");
    }

    private String best(long userId) {
        return jdbc.queryForObject(
                "SELECT rank || ' ' || board_key || ' ' || period_start FROM ranking_best WHERE user_id = ?",
                String.class,
                userId);
    }
}
