package project.study.group.translation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import project.study.group.translation.TranslationCacheRepository.CacheKey;
import project.study.group.translation.TranslationCacheRepository.Lease;

/** 권한이 확인된 응답의 문구만 번역하며, HTTP 앞에서 사용량을 예약한다. */
public class GroupTranslationService {
    private final TranslationCacheRepository cache;
    private final TranslationBudget budget;
    private final Map<TranslationProvider, TextTranslator> translators = new EnumMap<>(TranslationProvider.class);
    private final TranslationConfig.Settings settings;
    private final Clock clock;
    private final MeterRegistry metrics;

    public GroupTranslationService(
            TranslationCacheRepository cache,
            TranslationBudget budget,
            List<TextTranslator> translators,
            TranslationConfig.Settings settings,
            Clock clock,
            MeterRegistry metrics) {
        this.cache = cache;
        this.budget = budget;
        this.settings = settings;
        this.clock = clock;
        this.metrics = metrics;
        for (var translator : translators) this.translators.put(translator.provider(), translator);
    }

    public Map<CacheKey, String> translate(List<TranslationText> input, TranslationLanguage language) {
        if (!settings.enabled()) return Map.of();
        var candidates =
                input.stream().filter(t -> !t.source().isBlank()).distinct().toList();
        // 이미 요청 언어인 원문은 예약·호출·캐시 조회 없이 원문을 쓴다. 예전에 저장된 같은 언어 결과도 쓰지 않는다.
        var texts = candidates.stream()
                .filter(t -> !SourceLanguage.needsNoTranslation(t.source(), language))
                .sorted(Comparator.comparingLong(TranslationText::groupId).thenComparing(TranslationText::field))
                .toList();
        metrics.counter("group.translation.skipped", "language", language.locale())
                .increment(candidates.size() - texts.size());
        if (texts.isEmpty()) return Map.of();
        Map<CacheKey, String> results = new HashMap<>();
        List<Lease> leases = List.of();
        try {
            cache.find(texts, language).forEach((key, value) -> results.put(key, value.text()));
            metrics.counter("group.translation.cache", "result", "hit").increment(results.size());
            leases = cache.claim(
                    texts.stream()
                            .filter(t -> !results.containsKey(key(t, language)))
                            .toList(),
                    language);
            dispatch(new ArrayList<>(leases), language, results);
        } catch (DataAccessException ignored) {
            /* 저장소 문제일 때 외부 호출을 계속하지 않는다. */
        } finally {
            release(leases);
        }
        metrics.counter("group.translation.fallback", "language", language.locale())
                .increment(texts.size() - results.size());
        return Map.copyOf(results);
    }

    private void dispatch(List<Lease> pending, TranslationLanguage language, Map<CacheKey, String> results) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        for (TranslationProvider provider : TranslationProvider.values()) {
            TextTranslator translator = translators.get(provider);
            if (translator == null || !settings.active(provider)) continue;
            while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
                if (TranslationHttp.remaining(deadline).isZero()) return;
                if (!attempt(provider, language, pending, results, deadline)) break;
            }
        }
    }

    private boolean attempt(
            TranslationProvider provider,
            TranslationLanguage language,
            List<Lease> pending,
            Map<CacheKey, String> results,
            long deadline) {
        List<Lease> batch = batch(pending, budget.available(provider));
        if (batch.isEmpty()) return false;
        long characters = batch.stream().mapToLong(l -> l.source().characters()).sum();
        if (!budget.tryReserve(provider, characters)) return false;
        metrics.counter("group.translation.reserved.characters", "provider", provider.name())
                .increment(characters);
        var reply = call(translators.get(provider), batch, language, TranslationHttp.remaining(deadline));
        metrics.counter(
                        "group.translation.provider",
                        "provider",
                        provider.name(),
                        "outcome",
                        reply.failure().name())
                .increment();
        if (reply.failure() != TextTranslator.Failure.NONE) {
            block(provider, reply);
            return false;
        }
        if (!valid(reply, batch.size())) return false;
        publish(batch, language, provider, reply.texts(), results);
        pending.removeAll(batch);
        return true;
    }

    private static boolean valid(TextTranslator.TranslationReply reply, int count) {
        return reply.texts().size() == count && reply.texts().stream().noneMatch(t -> t == null || t.isBlank());
    }

    private void publish(
            List<Lease> batch,
            TranslationLanguage language,
            TranslationProvider provider,
            List<String> texts,
            Map<CacheKey, String> results) {
        for (int i = 0; i < batch.size(); i++) {
            Lease lease = batch.get(i);
            if (cache.complete(lease, texts.get(i), provider)) results.put(key(lease.source(), language), texts.get(i));
        }
    }

    private TextTranslator.TranslationReply call(
            TextTranslator translator, List<Lease> batch, TranslationLanguage language, Duration remaining) {
        Duration timeout = remaining.compareTo(Duration.ofSeconds(2)) < 0 ? remaining : Duration.ofSeconds(2);
        try {
            return translator.translate(
                    batch.stream().map(l -> l.source().source()).toList(), language, timeout);
        } catch (RuntimeException ignored) {
            return TextTranslator.TranslationReply.failed(TextTranslator.Failure.TEMPORARY);
        }
    }

    private static List<Lease> batch(List<Lease> pending, long available) {
        List<Lease> batch = new ArrayList<>();
        long remaining = Math.min(5000, available);
        for (Lease lease : pending) {
            long characters = lease.source().characters();
            if (characters > remaining) continue;
            batch.add(lease);
            remaining -= characters;
            if (batch.size() == 50) break;
        }
        return batch;
    }

    private void block(TranslationProvider provider, TextTranslator.TranslationReply reply) {
        switch (reply.failure()) {
            case AUTH -> budget.disable(provider);
            case QUOTA -> {
                if (provider == TranslationProvider.GOOGLE) {
                    var at = clock.instant().atZone(ZoneId.of("America/Los_Angeles"));
                    budget.blockUntil(
                            provider,
                            at.toLocalDate()
                                    .plusDays(1)
                                    .atStartOfDay(at.getZone())
                                    .toInstant());
                } else budget.disable(provider);
            }
            case RATE_LIMIT ->
                budget.blockUntil(
                        provider,
                        reply.retryAt() == null || !reply.retryAt().isAfter(clock.instant())
                                ? clock.instant().plusSeconds(60)
                                : reply.retryAt());
            default -> {
                /* 해당 필드는 다른 무료 공급자를 시도한다. */
            }
        }
    }

    private void release(List<Lease> leases) {
        if (leases.isEmpty()) return;
        try {
            cache.fail(leases);
        } catch (DataAccessException ignored) {
            /* 만료된 임차권은 다음 요청이 회수한다. */
        }
    }

    private static CacheKey key(TranslationText text, TranslationLanguage language) {
        return new CacheKey(text.groupId(), text.field(), language);
    }
}
