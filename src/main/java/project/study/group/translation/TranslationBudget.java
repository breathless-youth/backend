package project.study.group.translation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 모든 인스턴스가 같은 공급자 행을 잠그고 HTTP 전에 사용량을 영구 예약한다. */
public class TranslationBudget {
    public record Limits(long googleDaily, long googleMonthly, long azureMonthly, long deepLLifetime) {
        public Limits {
            check(googleDaily, 13000);
            check(googleMonthly, 400000);
            check(azureMonthly, 2000000);
            check(deepLLifetime, 1000000);
        }

        private static void check(long value, long maximum) {
            if (value <= 0 || value > maximum)
                throw new IllegalArgumentException("Translation limit exceeds free guard");
        }
    }

    private record State(boolean initialized, boolean suspended, Instant blockedUntil, String day, String month) {}

    private record Bucket(String type, String key, String previousKey, long limit) {}

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Limits limits;

    public TranslationBudget(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock, Limits limits) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limits = limits;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean tryReserve(TranslationProvider provider, long characters) {
        if (characters <= 0) return false;
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                State state = lock(provider).orElse(null);
                if (!ready(state)) return false;
                List<Bucket> buckets = buckets(provider, state);
                if (remaining(provider, buckets) < characters) return false;
                for (Bucket bucket : buckets) reserve(provider, bucket, characters);
                advance(provider, buckets);
                return true;
            }));
        } catch (DataAccessException ignored) {
            return false;
        }
    }

    public long available(TranslationProvider provider) {
        try {
            Long value = tx.execute(status -> {
                State state = lock(provider).orElse(null);
                return ready(state) ? remaining(provider, buckets(provider, state)) : 0L;
            });
            return value == null ? 0 : value;
        } catch (DataAccessException ignored) {
            return 0;
        }
    }

    public void blockUntil(TranslationProvider provider, Instant until) {
        jdbc.sql(
                        "UPDATE group_translation_provider_state SET blocked_until = GREATEST(blocked_until, :until) WHERE provider = :p")
                .param("p", provider.name())
                .param("until", Timestamp.from(until))
                .update();
    }

    public void disable(TranslationProvider provider) {
        jdbc.sql("UPDATE group_translation_provider_state SET suspended = true WHERE provider = :p")
                .param("p", provider.name())
                .update();
    }

    private Optional<State> lock(TranslationProvider provider) {
        return jdbc.sql("SELECT * FROM group_translation_provider_state WHERE provider = :p FOR UPDATE")
                .param("p", provider.name())
                .query((rs, n) -> {
                    Timestamp blocked = rs.getTimestamp("blocked_until");
                    return new State(
                            rs.getBoolean("initialized"),
                            rs.getBoolean("suspended"),
                            blocked == null ? null : blocked.toInstant(),
                            rs.getString("day_key"),
                            rs.getString("month_key"));
                })
                .optional();
    }

    private boolean ready(State state) {
        return state != null
                && state.initialized()
                && !state.suspended()
                && (state.blockedUntil() == null || !state.blockedUntil().isAfter(clock.instant()));
    }

    private List<Bucket> buckets(TranslationProvider provider, State state) {
        var at = clock.instant().atZone(provider == TranslationProvider.GOOGLE ? PACIFIC : ZoneOffset.UTC);
        String day = at.toLocalDate().toString();
        String month = YearMonth.from(at).toString();
        return switch (provider) {
            case GOOGLE ->
                List.of(
                        new Bucket("DAY", day, state.day(), limits.googleDaily()),
                        new Bucket("MONTH", month, state.month(), limits.googleMonthly()));
            case AZURE -> List.of(new Bucket("MONTH", month, state.month(), limits.azureMonthly()));
            case DEEPL -> List.of(new Bucket("LIFETIME", "ALL", "ALL", limits.deepLLifetime()));
        };
    }

    private long remaining(TranslationProvider provider, List<Bucket> buckets) {
        long available = Long.MAX_VALUE;
        for (Bucket bucket : buckets) {
            if (bucket.previousKey() == null || bucket.key().compareTo(bucket.previousKey()) < 0) return 0;
            Optional<Long> previous = used(provider, bucket.type(), bucket.previousKey());
            if (previous.isEmpty()) return 0; // 현재/직전 장부 유실을 새 무료량으로 간주하지 않는다.
            long current = bucket.key().equals(bucket.previousKey())
                    ? previous.get()
                    : used(provider, bucket.type(), bucket.key()).orElse(0L);
            available = Math.min(available, Math.max(0, bucket.limit() - current));
        }
        return available;
    }

    private Optional<Long> used(TranslationProvider provider, String type, String key) {
        return jdbc.sql(
                        "SELECT reserved_characters FROM group_translation_usage WHERE provider = :p AND period_type = :type AND period_key = :key")
                .param("p", provider.name())
                .param("type", type)
                .param("key", key)
                .query(Long.class)
                .optional();
    }

    private void reserve(TranslationProvider provider, Bucket bucket, long characters) {
        jdbc.sql("""
                INSERT INTO group_translation_usage(provider, period_type, period_key, reserved_characters)
                VALUES (:p, :type, :key, :chars)
                ON CONFLICT (provider, period_type, period_key) DO UPDATE
                   SET reserved_characters = group_translation_usage.reserved_characters + EXCLUDED.reserved_characters
                """)
                .param("p", provider.name())
                .param("type", bucket.type())
                .param("key", bucket.key())
                .param("chars", characters)
                .update();
    }

    private void advance(TranslationProvider provider, List<Bucket> buckets) {
        for (Bucket bucket : buckets) {
            if (bucket.type().equals("LIFETIME")) continue;
            String column = bucket.type().equals("DAY") ? "day_key" : "month_key";
            jdbc.sql("UPDATE group_translation_provider_state SET " + column + " = :key WHERE provider = :p")
                    .param("p", provider.name())
                    .param("key", bucket.key())
                    .update();
        }
    }
}
