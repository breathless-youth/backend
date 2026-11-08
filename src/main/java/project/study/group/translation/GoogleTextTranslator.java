package project.study.group.translation;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class GoogleTextTranslator implements TextTranslator {
    private final TranslationHttp http;
    private final String key;

    public GoogleTextTranslator(String key) {
        this(URI.create("https://translation.googleapis.com"), key);
    }

    GoogleTextTranslator(URI base, String key) {
        this.http = new TranslationHttp(base);
        this.key = key;
    }

    public TranslationProvider provider() {
        return TranslationProvider.GOOGLE;
    }

    public TranslationReply translate(List<String> texts, TranslationLanguage language, Duration timeout) {
        var wire = http.call(
                "/language/translate/v2",
                Map.of("q", texts, "target", language.providerCode(provider()), "model", "nmt", "format", "text"),
                Map.of("X-Goog-Api-Key", key),
                timeout);
        if (wire.status() != 200) return TranslationHttp.failure(wire, provider());
        return TranslationHttp.translated(
                wire,
                wire.body() == null ? null : wire.body().path("data").path("translations"),
                texts.size(),
                "translatedText",
                true);
    }
}
