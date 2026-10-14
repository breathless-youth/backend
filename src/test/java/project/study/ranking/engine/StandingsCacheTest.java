package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import project.study.room.support.MutableClock;

class StandingsCacheTest {

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-10T06:00:00Z"));
    private final StandingsCache cache = new StandingsCache(clock);
    private final AtomicInteger loads = new AtomicInteger();

    private int get() {
        return cache.get("k", Duration.ofSeconds(10), loads::incrementAndGet);
    }

    @Test
    void 수명_안에서는_다시_계산하지_않고_지나면_다시_계산한다() {
        assertThat(get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(9));
        assertThat(get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(get()).isEqualTo(2);
    }

    @Test
    void 만료된_지_10분_넘은_키는_지운다() {
        get();
        clock.advance(Duration.ofMinutes(11));
        cache.evictExpired();
        clock.set(Instant.parse("2026-10-10T06:00:05Z"));

        assertThat(get()).isEqualTo(2);
    }
}
