package project.study.group.translation;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DeepLTextTranslator implements TextTranslator {
    public record FreeUsage(long usedCharacters, long limitCharacters) {}

    private final TranslationHttp http;
    private final Map<String, String> auth;
    private volatile Set<String> targets = Set.of();

    public DeepLTextTranslator(String key) {
        this(URI.create("https://api-free.deepl.com"), key);
    }

    DeepLTextTranslator(URI base, String key) {
        this.http = new TranslationHttp(base);
        this.auth = Map.of("Authorization", "DeepL-Auth-Key " + key);
    }

    public TranslationProvider provider() {
        return TranslationProvider.DEEPL;
    }

    public TranslationReply translate(List<String> texts, TranslationLanguage language, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var usage = freeUsage(TranslationHttp.remaining(deadline));
        if (usage.isEmpty()) return TranslationReply.failed(Failure.TEMPORARY);
        if (usage.get().limitCharacters() != 1000000) return TranslationReply.failed(Failure.AUTH);
        long characters =
                texts.stream().mapToLong(t -> t.codePointCount(0, t.length())).sum();
        if (characters > usage.get().limitCharacters() - usage.get().usedCharacters())
            return TranslationReply.failed(Failure.QUOTA);
        if (!supportedTargets(TranslationHttp.remaining(deadline)).contains(language.providerCode(provider())))
            return TranslationReply.failed(Failure.UNSUPPORTED);
        var wire = http.call(
                "/v2/translate",
                Map.of("text", texts, "target_lang", language.providerCode(provider())),
                auth,
                TranslationHttp.remaining(deadline));
        if (wire.status() != 200) return TranslationHttp.failure(wire, provider());
        return TranslationHttp.translated(
                wire, wire.body() == null ? null : wire.body().path("translations"), texts.size(), "text", false);
    }

    Optional<FreeUsage> freeUsage(Duration timeout) {
        var wire = http.call("/v2/usage", null, auth, timeout);
        if (wire.status() != 200 || wire.body() == null) return Optional.empty();
        var used = wire.body().path("character_count");
        var limit = wire.body().path("character_limit");
        if (!used.isIntegralNumber() || !limit.isIntegralNumber() || used.asLong() < 0 || limit.asLong() <= 0)
            return Optional.empty();
        return Optional.of(new FreeUsage(used.asLong(), limit.asLong()));
    }

    Set<String> supportedTargets(Duration timeout) {
        if (!targets.isEmpty()) return targets;
        var wire = http.call("/v3/languages?resource=translate_text", null, auth, timeout);
        if (wire.status() != 200 || wire.body() == null || !wire.body().isArray()) return Set.of();
        Set<String> found = new HashSet<>();
        for (var entry : wire.body())
            if (entry.path("lang").isString() && entry.path("usable_as_target").asBoolean(false))
                found.add(entry.path("lang").asString().toUpperCase(java.util.Locale.ROOT));
        targets = Set.copyOf(found);
        return targets;
    }
}
