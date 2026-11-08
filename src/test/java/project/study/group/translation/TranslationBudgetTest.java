package project.study.group.translation;

import static org.assertj.core.api.Assertions.*;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.translation.TranslationProvider.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import project.study.TestcontainersConfiguration;
import project.study.room.support.MutableClock;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TranslationBudgetTest {
    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    MutableClock clock;
    TranslationBudget budget;

    @BeforeEach
    void setup() {
        clock = MutableClock.at(Instant.parse("2026-11-08T10:00:00Z"));
        jdbc.sql("delete from group_translation_usage").update();
        jdbc.sql(
                        "update group_translation_provider_state set initialized = false, suspended = false, blocked_until = null, day_key = null, month_key = null")
                .update();
        budget = fresh();
    }

    TranslationBudget fresh() {
        return new TranslationBudget(
                jdbc, manager, clock, new TranslationBudget.Limits(13000, 400000, 2000000, 1000000));
    }

    void seed(TranslationProvider provider, long day, long month, long lifetime) {
        jdbc.sql(
                        "update group_translation_provider_state set initialized = true, day_key = '2026-11-08', month_key = '2026-11' where provider = :p")
                .param("p", provider.name())
                .update();
        insert(provider, "DAY", "2026-11-08", day);
        insert(provider, "MONTH", "2026-11", month);
        insert(provider, "LIFETIME", "ALL", lifetime);
    }

    void insert(TranslationProvider provider, String type, String key, long value) {
        jdbc.sql("insert into group_translation_usage values (:p, :type, :key, :value)")
                .param("p", provider.name())
                .param("type", type)
                .param("key", key)
                .param("value", value)
                .update();
    }

    long usage(String provider, String type) {
        return jdbc.sql(
                        "select reserved_characters from group_translation_usage where provider = :p and period_type = :type order by period_key desc limit 1")
                .param("p", provider)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    @Test
    void checksDayAndMonthTogetherWithoutPartialReservation() {
        seed(GOOGLE, 12999, 399999, 0);
        assertThat(budget.tryReserve(GOOGLE, 1)).isTrue();
        assertThat(fresh().tryReserve(GOOGLE, 1)).isFalse();
        assertThat(usage("GOOGLE", "DAY")).isEqualTo(13000);
        assertThat(usage("GOOGLE", "MONTH")).isEqualTo(400000);
    }

    @Test
    void exhaustedMonthDoesNotIncreaseFreshDayAndProvidersAreIndependent() {
        seed(GOOGLE, 0, 399999, 0);
        seed(AZURE, 0, 0, 0);
        assertThat(budget.tryReserve(GOOGLE, 2)).isFalse();
        assertThat(usage("GOOGLE", "DAY")).isZero();
        assertThat(budget.tryReserve(AZURE, 2)).isTrue();
        assertThat(usage("AZURE", "MONTH")).isEqualTo(2);
    }

    @Test
    void concurrentServersNeverOverbook() throws Exception {
        seed(GOOGLE, 0, 0, 0);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int n = 0; n < 100; n++) calls.add(() -> fresh().tryReserve(GOOGLE, 200));
        assertThat(race(calls).stream().filter(Boolean::booleanValue).count()).isEqualTo(65);
        assertThat(usage("GOOGLE", "DAY")).isEqualTo(13000);
    }

    @Test
    void pacificMidnightAndMonthRollOverButDeepLLifetimeDoesNot() {
        seed(GOOGLE, 13000, 400000, 0);
        seed(DEEPL, 0, 0, 999999);
        assertThat(budget.tryReserve(DEEPL, 1)).isTrue();
        clock.set(Instant.parse("2026-12-01T07:59:59Z"));
        assertThat(budget.tryReserve(GOOGLE, 1)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(budget.tryReserve(GOOGLE, 1)).isTrue();
        assertThat(budget.tryReserve(DEEPL, 1)).isFalse();
        clock.set(Instant.parse("2027-01-01T09:00:00Z"));
        assertThat(fresh().tryReserve(DEEPL, 1)).isFalse();
    }

    @Test
    void dstChangesDoNotCreateAnExtraDailyAllowance() {
        seed(GOOGLE, 0, 0, 0);
        clock.set(Instant.parse("2027-03-14T09:59:59Z"));
        assertThat(budget.tryReserve(GOOGLE, 13000)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(budget.tryReserve(GOOGLE, 1)).isFalse();
        clock.set(Instant.parse("2027-03-15T07:00:00Z"));
        assertThat(budget.tryReserve(GOOGLE, 1)).isTrue();
    }

    @Test
    void azureUsesUtcMonthAndHonorsItsOwnCeiling() {
        seed(AZURE, 0, 2000000, 0);
        clock.set(Instant.parse("2026-11-30T23:59:59Z"));
        assertThat(budget.tryReserve(AZURE, 1)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(budget.tryReserve(AZURE, 1)).isTrue();
    }

    @Test
    void missingUsageInvalidAmountsAndBlocksFailClosed() {
        assertThat(budget.tryReserve(GOOGLE, 1)).isFalse();
        seed(GOOGLE, 0, 0, 0);
        assertThat(budget.tryReserve(GOOGLE, 0)).isFalse();
        assertThat(budget.tryReserve(GOOGLE, Long.MAX_VALUE)).isFalse();
        assertThat(budget.tryReserve(GOOGLE, -1)).isFalse();
        budget.blockUntil(GOOGLE, clock.instant().plusSeconds(60));
        assertThat(fresh().available(GOOGLE)).isZero();
        clock.advance(Duration.ofSeconds(60));
        assertThat(budget.tryReserve(GOOGLE, 1)).isTrue();
        jdbc.sql("delete from group_translation_usage where provider = 'GOOGLE' and period_type = 'DAY'")
                .update();
        assertThat(fresh().tryReserve(GOOGLE, 1)).isFalse();
        budget.disable(GOOGLE);
        assertThat(fresh().available(GOOGLE)).isZero();
    }

    @Test
    void configurationCannotRaiseApprovedCeilings() {
        assertThatThrownBy(() -> new TranslationBudget.Limits(15000, 400000, 2000000, 1000000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void midnightDuringReservationDoesNotMarkAnUncreatedBucketAsCurrent() {
        seed(GOOGLE, 0, 0, 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.time.Clock ticking = new java.time.Clock() {
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return Instant.parse(calls.getAndIncrement() == 0 ? "2026-11-09T07:59:59Z" : "2026-11-09T08:00:00Z");
            }
        };
        var atMidnight = new TranslationBudget(
                jdbc, manager, ticking, new TranslationBudget.Limits(13000, 400000, 2000000, 1000000));
        assertThat(atMidnight.tryReserve(GOOGLE, 13000)).isTrue();
        assertThat(atMidnight.available(GOOGLE)).isEqualTo(13000);
    }
}
