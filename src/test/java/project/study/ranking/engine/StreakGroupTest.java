package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StreakGroupTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    private static List<RankingEntry> days(int... values) {
        return IntStream.range(0, values.length)
                .mapToObj(i -> RankingEntry.of(i + 1, "u" + (i + 1), values[i], T0))
                .toList();
    }

    @Test
    void 같은_일수끼리_묶는다() {
        assertThat(StreakGroup.of(days(5, 5, 4, 4, 4, 3)))
                .containsExactly(new StreakGroup(5, 0, 2), new StreakGroup(4, 2, 3), new StreakGroup(3, 5, 1));
    }

    @Test
    void 자리가_속한_묶음을_찾는다() {
        List<StreakGroup> groups = StreakGroup.of(days(5, 5, 4, 4, 4, 3));

        assertThat(StreakGroup.indexContaining(groups, 3)).isEqualTo(1);
        assertThat(StreakGroup.indexContaining(groups, 5)).isEqualTo(2);
    }

    @Test
    void 빈_순위표는_묶음이_없다() {
        assertThat(StreakGroup.of(List.of())).isEmpty();
    }
}
