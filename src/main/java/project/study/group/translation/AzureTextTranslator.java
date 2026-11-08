package project.study.group.translation;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

public class AzureTextTranslator implements TextTranslator {
    private final TranslationHttp http;
    private final String key;
    private final String region;

    public AzureTextTranslator(String key, String region) {
        this(URI.create("https://api.cognitive.microsofttranslator.com"), key, region);
    }

    AzureTextTranslator(URI base, String key, String region) {
        this.http = new TranslationHttp(base);
        this.key = key;
        this.region = region;
    }

    public TranslationProvider provider() {
        return TranslationProvider.AZURE;
    }

    public TranslationReply translate(List<String> texts, TranslationLanguage language, Duration timeout) {
        var wire = http.call(
                "/translate?api-version=3.0&to=" + language.providerCode(provider()),
                texts.stream().map(t -> Map.of("Text", t)).toList(),
                Map.of("Ocp-Apim-Subscription-Key", key, "Ocp-Apim-Subscription-Region", region),
                timeout);
        if (wire.status() != 200) return TranslationHttp.failure(wire, provider());
        if (wire.body() == null || !wire.body().isArray()) return TranslationReply.failed(Failure.INVALID_RESPONSE);
        ArrayNode flattened = JsonMapper.builder().build().createArrayNode();
        for (var item : wire.body()) {
            var translations = item.path("translations");
            if (!translations.isArray() || translations.size() != 1)
                return TranslationReply.failed(Failure.INVALID_RESPONSE);
            flattened.add(translations.get(0));
        }
        return TranslationHttp.translated(wire, flattened, texts.size(), "text", false);
    }
}
