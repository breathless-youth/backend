package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ExportWindowTest {

    private final ExportWindow window = new ExportWindow(LocalDate.of(2026, 10, 12));

    @Test
    void KST_하루_창은_전날_UTC_15시부터_그날_UTC_14시까지다() {
        assertThat(window.startParam()).isEqualTo("20261011T15");
        assertThat(window.endParam()).isEqualTo("20261012T14");
        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-10-12T15:00:00Z"));
    }

    @Test
    void 창은_끝나고_3시간이_지나야_준비된다() {
        assertThat(window.isReady(Instant.parse("2026-10-12T17:59:59Z"))).isFalse();
        assertThat(window.isReady(Instant.parse("2026-10-12T18:00:00Z"))).isTrue();
    }

    @Test
    void 월초_연초_경계도_전날_15시로_넘어간다() {
        ExportWindow newYear = new ExportWindow(LocalDate.of(2027, 1, 1));

        assertThat(newYear.startParam()).isEqualTo("20261231T15");
        assertThat(newYear.endParam()).isEqualTo("20270101T14");
    }
}
