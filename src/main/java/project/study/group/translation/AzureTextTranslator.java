package project.study.group.translation;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
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
        List<Integer> undetected = new ArrayList<>();
        for (var item : wire.body()) {
            var translations = item.path("translations");
            if (!translations.isArray() || translations.size() != 1)
                return TranslationReply.failed(Failure.INVALID_RESPONSE);
            if (undetected(item)) undetected.add(flattened.size());
            flattened.add(translations.get(0));
        }
        return withoutUndetected(TranslationHttp.translated(wire, flattened, texts.size(), "text", false), undetected);
    }

    private static TranslationReply withoutUndetected(TranslationReply reply, List<Integer> undetected) {
        if (reply.failure() != Failure.NONE || undetected.isEmpty()) return reply;
        var result = new ArrayList<>(reply.texts());
        for (int index : undetected) result.set(index, null);
        return new TranslationReply(Collections.unmodifiableList(result), Failure.NONE, null);
    }

    /**
     * 원문 언어를 못 알아보면(감지 확신도 0) Azure는 원문을 거의 그대로 돌려준다. 문언문이 그렇다.
     * 이 결과를 번역으로 저장하지 않도록 null로 비워 다음 공급자에 맡긴다.
     */
    private static boolean undetected(JsonNode item) {
        JsonNode score = item.path("detectedLanguage").path("score");
        return score.isNumber() && score.asDouble() <= 0;
    }
}
