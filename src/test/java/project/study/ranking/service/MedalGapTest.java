package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;

/** gap = max(0, 3위 값 − 내 값, 1800 − 내 값) — 참가 전이면 내 값 0, 3위가 없으면 3위 값 0 (BY-828 §7.2). */
class MedalGapTest {

    private static final Instant T = Instant.parse("2026-10-10T06:00:00Z");

    private static RankingEntry entry(long userId, double value) {
        return RankingEntry.of(userId, "u" + userId, value, T);
    }

    @Test
    void 삼위_안이고_30분을_넘었으면_0이다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000)), 1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isZero();
    }

    @Test
    void 삼위_밖이면_삼위_값까지_남은_양이다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000), entry(4, 2500)), 3);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(500);
    }

    @Test
    void 삼위_안이어도_30분이_안_되면_30분까지_남은_양이다() {
        Placement placement = new Placement(List.of(entry(1, 1000)), 0);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(800);
    }

    @Test
    void 삼위_밖이면_삼위와_값이_같아도_최소_1이다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000), entry(4, 3000)), 3);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(1);
    }

    @Test
    void 참가_전이고_세_명이_안_되면_30분이다() {
        Placement placement = new Placement(List.of(entry(2, 1200)), -1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(1800);
    }

    @Test
    void 참가_전이면_삼위_값_전부다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000)), -1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(3000);
    }
}
