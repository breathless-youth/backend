package project.study.group.translation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 짧은 쓰기 트랜잭션으로 작업권을 관리하는 그룹 문구의 공유 캐시. */
public class TranslationCacheRepository {
    public record CacheKey(long groupId, TranslationField field, TranslationLanguage language) {}

    public record CachedText(String sourceHash, String text, TranslationProvider provider) {}

    public record Lease(TranslationText source, TranslationLanguage language, UUID token) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TranslationCacheRepository(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<CacheKey, CachedText> find(List<TranslationText> texts, TranslationLanguage language) {
        if (texts.isEmpty()) return Map.of();
        Map<CacheKey, String> requested = new HashMap<>();
        texts.forEach(t -> requested.put(new CacheKey(t.groupId(), t.field(), language), t.sourceHash()));
        Map<CacheKey, CachedText> results = new HashMap<>();
        jdbc.sql("""
                SELECT group_id, field, source_hash, translated_text, provider
                  FROM group_translation_cache
                 WHERE group_id IN (:ids) AND target_locale = :locale AND translated_text IS NOT NULL
                """)
                .param(
                        "ids",
                        texts.stream().map(TranslationText::groupId).distinct().toList())
                .param("locale", language.locale())
                .query((rs, n) -> {
                    CacheKey key = new CacheKey(
                            rs.getLong("group_id"), TranslationField.valueOf(rs.getString("field")), language);
                    String hash = rs.getString("source_hash");
                    if (hash.equals(requested.get(key)))
                        results.put(
                                key,
                                new CachedText(
                                        hash,
                                        rs.getString("translated_text"),
                                        TranslationProvider.valueOf(rs.getString("provider"))));
                    return key;
                })
                .list();
        return results;
    }

    public List<Lease> claim(List<TranslationText> texts, TranslationLanguage language) {
        return tx.execute(status -> {
            List<Lease> leases = new ArrayList<>();
            texts.stream()
                    .distinct()
                    .sorted(Comparator.comparingLong(TranslationText::groupId).thenComparing(TranslationText::field))
                    .forEach(text -> {
                        Lease lease = new Lease(text, language, UUID.randomUUID());
                        if (claimOne(lease)) leases.add(lease);
                    });
            return leases;
        });
    }

    private boolean claimOne(Lease lease) {
        if (!lockCurrentSource(lease.source())) return false;
        String column = column(lease.source());
        String sql = """
                INSERT INTO group_translation_cache
                    (group_id, field, target_locale, source_hash, lease_token, lease_until, next_attempt_at, updated_at)
                SELECT :id, :field, :locale, :hash, :token, :until, :now, :now
                  FROM study_group g WHERE g.id = :id AND g.deleted_at IS NULL AND g.%s = :source
                ON CONFLICT (group_id, field, target_locale) DO UPDATE
                   SET source_hash = EXCLUDED.source_hash, translated_text = NULL, provider = NULL,
                       lease_token = EXCLUDED.lease_token, lease_until = EXCLUDED.lease_until,
                       next_attempt_at = EXCLUDED.next_attempt_at, updated_at = EXCLUDED.updated_at
                 WHERE group_translation_cache.source_hash <> EXCLUDED.source_hash
                    OR (group_translation_cache.translated_text IS NULL
                        AND group_translation_cache.lease_until <= :now
                        AND group_translation_cache.next_attempt_at <= :now)
                """.formatted(column);
        return bind(jdbc.sql(sql), lease)
                        .param("until", Timestamp.from(clock.instant().plusSeconds(10)))
                        .update()
                == 1;
    }

    public boolean complete(Lease lease, String text, TranslationProvider provider) {
        if (text == null || text.isBlank()) return false;
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lockCurrentSource(lease.source())) return false;
            return bind(jdbc.sql("""
                UPDATE group_translation_cache c
                   SET translated_text = :text, provider = :provider, lease_token = NULL, updated_at = :now
                  FROM study_group g
                 WHERE c.group_id = :id AND c.field = :field AND c.target_locale = :locale
                   AND c.source_hash = :hash AND c.lease_token = :token AND c.lease_until > :now
                   AND g.id = c.group_id AND g.deleted_at IS NULL AND g.%s = :source
                """.formatted(column(lease.source()))), lease)
                            .param("text", text)
                            .param("provider", provider.name())
                            .update()
                    == 1;
        }));
    }

    public void fail(List<Lease> leases) {
        tx.executeWithoutResult(status -> leases.forEach(lease -> bind(jdbc.sql("""
                UPDATE group_translation_cache SET lease_token = NULL, lease_until = :now,
                    next_attempt_at = :next, updated_at = :now
                 WHERE group_id = :id AND field = :field AND target_locale = :locale
                   AND source_hash = :hash AND lease_token = :token AND translated_text IS NULL
                """), lease)
                .param("next", Timestamp.from(clock.instant().plusSeconds(60)))
                .update()));
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Lease lease) {
        return query.param("id", lease.source().groupId())
                .param("field", lease.source().field().name())
                .param("locale", lease.language().locale())
                .param("hash", lease.source().sourceHash())
                .param("source", lease.source().source())
                .param("token", lease.token())
                .param("now", Timestamp.from(clock.instant()));
    }

    private static String column(TranslationText text) {
        return text.field() == TranslationField.NAME ? "name" : "description";
    }

    private boolean lockCurrentSource(TranslationText text) {
        // 그룹 → 캐시 순서. 옛 INSERT SELECT 스냅샷이 새 conflict 행을 덮지 못하게 한다.
        return jdbc.sql("SELECT id FROM study_group WHERE id = :id AND deleted_at IS NULL AND " + column(text)
                        + " = :source FOR SHARE")
                .param("id", text.groupId())
                .param("source", text.source())
                .query(Long.class)
                .optional()
                .isPresent();
    }
}
