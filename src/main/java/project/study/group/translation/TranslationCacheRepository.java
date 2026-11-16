package project.study.group.translation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 짧은 쓰기 트랜잭션으로 작업권을 관리하는 번역 공유 캐시. 칸의 {@link TranslationField.Subject}가 캐시 테이블과 원문 테이블을 정한다 — 테이블
 * 이름은 enum 상수에서만 오고 요청 값이 SQL에 들어가지 않는다.
 */
public class TranslationCacheRepository {
    public record CacheKey(long subjectId, TranslationField field, TranslationLanguage language) {}

    public record CachedText(String sourceHash, String text, TranslationProvider provider) {}

    public record Lease(TranslationText source, TranslationLanguage language, UUID token) {}

    /** 잠그는 순서 — 원문 종류, 행, 칸. 같은 요청 안의 잠금이 항상 같은 순서로 잡히게 한다. */
    static final Comparator<TranslationText> ORDER = Comparator.comparing((TranslationText t) -> t.field().subject)
            .thenComparingLong(TranslationText::subjectId)
            .thenComparing(TranslationText::field);

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
        texts.forEach(t -> requested.put(new CacheKey(t.subjectId(), t.field(), language), t.sourceHash()));
        Map<CacheKey, CachedText> results = new HashMap<>();
        texts.stream()
                .collect(Collectors.groupingBy(t -> t.field().subject, LinkedHashMap::new, Collectors.toList()))
                .forEach((subject, group) -> findIn(subject, group, language, requested, results));
        return results;
    }

    private void findIn(
            TranslationField.Subject subject,
            List<TranslationText> texts,
            TranslationLanguage language,
            Map<CacheKey, String> requested,
            Map<CacheKey, CachedText> results) {
        jdbc.sql("""
                SELECT %2$s AS subject_id, field, source_hash, translated_text, provider
                  FROM %1$s
                 WHERE %2$s IN (:ids) AND target_locale = :locale AND translated_text IS NOT NULL
                """.formatted(subject.cacheTable, subject.keyColumn))
                .param(
                        "ids",
                        texts.stream()
                                .map(TranslationText::subjectId)
                                .distinct()
                                .toList())
                .param("locale", language.locale())
                .query((rs, n) -> {
                    CacheKey key = new CacheKey(
                            rs.getLong("subject_id"), TranslationField.valueOf(rs.getString("field")), language);
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
    }

    public List<Lease> claim(List<TranslationText> texts, TranslationLanguage language) {
        return tx.execute(status -> {
            List<Lease> leases = new ArrayList<>();
            texts.stream().distinct().sorted(ORDER).forEach(text -> {
                Lease lease = new Lease(text, language, UUID.randomUUID());
                if (claimOne(lease)) leases.add(lease);
            });
            return leases;
        });
    }

    private boolean claimOne(Lease lease) {
        if (!lockCurrentSource(lease.source())) return false;
        TranslationField field = lease.source().field();
        TranslationField.Subject subject = field.subject;
        String sql = """
                INSERT INTO %1$s
                    (%2$s, field, target_locale, source_hash, lease_token, lease_until, next_attempt_at, updated_at)
                SELECT :id, :field, :locale, :hash, :token, :until, :now, :now
                  FROM %3$s s WHERE s.id = :id AND s.%4$s AND s.%5$s = :source
                ON CONFLICT (%2$s, field, target_locale) DO UPDATE
                   SET source_hash = EXCLUDED.source_hash, translated_text = NULL, provider = NULL,
                       lease_token = EXCLUDED.lease_token, lease_until = EXCLUDED.lease_until,
                       next_attempt_at = EXCLUDED.next_attempt_at, updated_at = EXCLUDED.updated_at
                 WHERE %1$s.source_hash <> EXCLUDED.source_hash
                    OR (%1$s.translated_text IS NULL
                        AND %1$s.lease_until <= :now
                        AND %1$s.next_attempt_at <= :now)
                """.formatted(
                subject.cacheTable, subject.keyColumn, subject.sourceTable, subject.liveCondition, field.sourceColumn);
        return bind(jdbc.sql(sql), lease)
                        .param("until", Timestamp.from(clock.instant().plusSeconds(10)))
                        .update()
                == 1;
    }

    public boolean complete(Lease lease, String text, TranslationProvider provider) {
        if (text == null || text.isBlank()) return false;
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lockCurrentSource(lease.source())) return false;
            TranslationField field = lease.source().field();
            TranslationField.Subject subject = field.subject;
            return bind(
                                    jdbc.sql("""
                UPDATE %1$s c
                   SET translated_text = :text, provider = :provider, lease_token = NULL, updated_at = :now
                  FROM %3$s s
                 WHERE c.%2$s = :id AND c.field = :field AND c.target_locale = :locale
                   AND c.source_hash = :hash AND c.lease_token = :token AND c.lease_until > :now
                   AND s.id = c.%2$s AND s.%4$s AND s.%5$s = :source
                """.formatted(
                                                    subject.cacheTable,
                                                    subject.keyColumn,
                                                    subject.sourceTable,
                                                    subject.liveCondition,
                                                    field.sourceColumn)),
                                    lease)
                            .param("text", text)
                            .param("provider", provider.name())
                            .update()
                    == 1;
        }));
    }

    public void fail(List<Lease> leases) {
        tx.executeWithoutResult(status -> leases.forEach(lease -> bind(
                        jdbc.sql("""
                UPDATE %1$s SET lease_token = NULL, lease_until = :now,
                    next_attempt_at = :next, updated_at = :now
                 WHERE %2$s = :id AND field = :field AND target_locale = :locale
                   AND source_hash = :hash AND lease_token = :token AND translated_text IS NULL
                """.formatted(
                                        lease.source().field().subject.cacheTable,
                                        lease.source().field().subject.keyColumn)),
                        lease)
                .param("next", Timestamp.from(clock.instant().plusSeconds(60)))
                .update()));
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Lease lease) {
        return query.param("id", lease.source().subjectId())
                .param("field", lease.source().field().name())
                .param("locale", lease.language().locale())
                .param("hash", lease.source().sourceHash())
                .param("source", lease.source().source())
                .param("token", lease.token())
                .param("now", Timestamp.from(clock.instant()));
    }

    private boolean lockCurrentSource(TranslationText text) {
        // 원문 행 → 캐시 순서. 옛 INSERT SELECT 스냅샷이 새 conflict 행을 덮지 못하게 한다.
        TranslationField.Subject subject = text.field().subject;
        return jdbc.sql("SELECT id FROM %s WHERE id = :id AND %s AND %s = :source FOR SHARE"
                        .formatted(subject.sourceTable, subject.liveCondition, text.field().sourceColumn))
                .param("id", text.subjectId())
                .param("now", Timestamp.from(clock.instant()))
                .param("source", text.source())
                .query(Long.class)
                .optional()
                .isPresent();
    }
}
