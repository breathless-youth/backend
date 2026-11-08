package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.translation.TextTranslator.Failure.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TextTranslatorTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    record Request(String path, String query, String auth, String region, JsonNode body) {}

    final List<Request> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    URI base;
    String response;
    int status = 200;
    String retryAfter;
    String targets =
            "[{\"lang\":\"en-US\",\"usable_as_target\":true},{\"lang\":\"zh-Hant\",\"usable_as_target\":true}]";
    String usage = "{\"character_count\":0,\"character_limit\":1000000}";
    long delay;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            capture(exchange);
            String body = path.endsWith("/usage") ? usage : path.endsWith("/languages") ? targets : response;
            if (delay > 0)
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            exchange.sendResponseHeaders(isMetadata(path) ? 200 : status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void capture(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("X-Goog-Api-Key");
        if (auth == null) auth = exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key");
        if (auth == null) auth = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(new Request(
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                auth,
                exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Region"),
                raw.isEmpty() ? null : JSON.readTree(raw)));
    }

    private static boolean isMetadata(String path) {
        return path.endsWith("/usage") || path.endsWith("/languages");
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void googleUsesOnlyNmtPlainTextAndHeaderKeyAndPreservesOrder() {
        response =
                "{\"data\":{\"translations\":[{\"translatedText\":\"Tom &amp; Jerry&#39;s\"},{\"translatedText\":\"Same\"}]}}";
        var reply = new GoogleTextTranslator(base, "fake-secret")
                .translate(List.of("원문", "Same"), TranslationLanguage.EN, Duration.ofSeconds(1));
        assertThat(reply.texts()).containsExactly("Tom & Jerry's", "Same");
        assertThat(reply.failure()).isEqualTo(NONE);
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/language/translate/v2");
            assertThat(r.query()).isNull();
            assertThat(r.auth()).isEqualTo("fake-secret");
            assertThat(r.body().path("model").asString()).isEqualTo("nmt");
            assertThat(r.body().path("format").asString()).isEqualTo("text");
            assertThat(r.body().has("source")).isFalse();
            assertThat(r.body().path("q").get(0).asString()).isEqualTo("원문");
        });
    }

    @Test
    void azureUsesStandardTextWithRegionAndTraditionalTarget() {
        response = "[{\"translations\":[{\"text\":\"一起學習\",\"to\":\"zh-Hant\"}]}]";
        var reply = new AzureTextTranslator(base, "fake-key", "koreacentral")
                .translate(List.of("함께 공부"), TranslationLanguage.ZH_TW, Duration.ofSeconds(1));
        assertThat(reply.texts()).containsExactly("一起學習");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.query()).isEqualTo("api-version=3.0&to=zh-Hant");
            assertThat(r.region()).isEqualTo("koreacentral");
            assertThat(r.auth()).isEqualTo("fake-key");
            assertThat(r.body().get(0).path("Text").asString()).isEqualTo("함께 공부");
        });
    }

    @Test
    void deepLVerifiesFreeBalanceAndTargetsBeforeTranslation() {
        response = "{\"translations\":[{\"text\":\"一起學習\",\"detected_source_language\":\"KO\"}]}";
        var reply = new DeepLTextTranslator(base, "fake-key:fx")
                .translate(List.of("함께 공부"), TranslationLanguage.ZH_TW, Duration.ofSeconds(1));
        assertThat(reply.texts()).containsExactly("一起學習");
        assertThat(requests).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/v2/translate");
            assertThat(r.auth()).isEqualTo("DeepL-Auth-Key fake-key:fx");
            assertThat(r.body().path("target_lang").asString()).isEqualTo("ZH-HANT");
        });
    }

    @Test
    void deepLDoesNotTranslateWhenFreeBalanceOrTargetIsUnavailable() {
        response = "{}";
        var adapter = new DeepLTextTranslator(base, "fake-key:fx");
        assertThat(adapter.translate(List.of("문구"), TranslationLanguage.JA, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(UNSUPPORTED);
        usage = "{\"character_count\":1000000,\"character_limit\":1000000}";
        assertThat(new DeepLTextTranslator(base, "fake-key:fx")
                        .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(QUOTA);
        assertThat(requests).noneMatch(r -> r.path().equals("/v2/translate"));
    }

    @Test
    void malformedPartialAndBlankResponsesAreNeverSuccess() {
        for (String body : List.of("{}", "not-json", "{\"data\":{\"translations\":[{\"translatedText\":\"\"}]}}")) {
            response = body;
            var reply = new GoogleTextTranslator(base, "key")
                    .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1));
            assertThat(reply.failure()).isEqualTo(INVALID_RESPONSE);
            assertThat(reply.texts()).isEmpty();
        }
        response = "{\"data\":{\"translations\":[{\"translatedText\":\"one\"}]}}";
        assertThat(new GoogleTextTranslator(base, "key")
                        .translate(List.of("하나", "둘"), TranslationLanguage.EN, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(INVALID_RESPONSE);
    }

    @Test
    void quotaAuthAndRateLimitsAreClassifiedWithoutRetrying() {
        response = "{\"error\":{\"code\":403001}}";
        status = 403;
        assertThat(new AzureTextTranslator(base, "key", "koreacentral")
                        .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(QUOTA);
        response = "{\"error\":{\"message\":\"Daily Limit Exceeded\"}}";
        assertThat(new GoogleTextTranslator(base, "key")
                        .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(QUOTA);
        status = 401;
        assertThat(new GoogleTextTranslator(base, "key")
                        .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1))
                        .failure())
                .isEqualTo(AUTH);
        status = 429;
        retryAfter = "120";
        var reply = new GoogleTextTranslator(base, "key")
                .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofSeconds(1));
        assertThat(reply.failure()).isEqualTo(RATE_LIMIT);
        assertThat(reply.retryAt()).isNotNull();
        assertThat(requests).hasSize(4);
    }

    @Test
    void timeoutProducesFallbackAndDoesNotRetry() {
        response = "{}";
        delay = 200;
        var reply = new GoogleTextTranslator(base, "key")
                .translate(List.of("문구"), TranslationLanguage.EN, Duration.ofMillis(70));
        assertThat(reply.failure()).isEqualTo(TEMPORARY);
        assertThat(requests).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void deepLUsesCurrentResourceCapabilitiesAndAnEnglishTargetVariant() {
        targets = "[{\"lang\":\"en\",\"usable_as_target\":false},{\"lang\":\"en-US\",\"usable_as_target\":true}]";
        response = "{\"translations\":[{\"text\":\"Study\"}]}";
        var reply = new DeepLTextTranslator(base, "fake-key:fx")
                .translate(List.of("공부"), TranslationLanguage.EN, Duration.ofSeconds(1));
        assertThat(reply.texts()).containsExactly("Study");
        assertThat(requests).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/v3/languages");
            assertThat(r.query()).isEqualTo("resource=translate_text");
        });
        assertThat(requests).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/v2/translate");
            assertThat(r.body().path("target_lang").asString()).isEqualTo("EN-US");
        });
    }

    @Test
    void fourTargetLocalesUseTheDocumentedCodesOnEveryProvider() {
        targets =
                "[{\"lang\":\"ko\",\"usable_as_target\":true},{\"lang\":\"en-US\",\"usable_as_target\":true},{\"lang\":\"ja\",\"usable_as_target\":true},{\"lang\":\"zh-Hans\",\"usable_as_target\":true},{\"lang\":\"zh-Hant\",\"usable_as_target\":true}]";
        String[] google = {"ko", "en", "ja", "zh-TW"};
        String[] azure = {"ko", "en", "ja", "zh-Hant"};
        String[] deepl = {"KO", "EN-US", "JA", "ZH-HANT"};
        var deepLAdapter = new DeepLTextTranslator(base, "fake:fx");
        for (int i = 0; i < 4; i++) {
            var language = TranslationLanguage.values()[i];
            requests.clear();
            response = "{\"data\":{\"translations\":[{\"translatedText\":\"translated\"}]}}";
            assertThat(new GoogleTextTranslator(base, "key")
                            .translate(List.of("공부"), language, Duration.ofSeconds(1))
                            .failure())
                    .isEqualTo(NONE);
            assertThat(requests.getLast().body().path("target").asString()).isEqualTo(google[i]);
            response = "[{\"translations\":[{\"text\":\"translated\"}]}]";
            assertThat(new AzureTextTranslator(base, "key", "koreacentral")
                            .translate(List.of("공부"), language, Duration.ofSeconds(1))
                            .failure())
                    .isEqualTo(NONE);
            assertThat(requests.getLast().query()).isEqualTo("api-version=3.0&to=" + azure[i]);
            response = "{\"translations\":[{\"text\":\"translated\"}]}";
            assertThat(deepLAdapter
                            .translate(List.of("공부"), language, Duration.ofSeconds(1))
                            .failure())
                    .isEqualTo(NONE);
            assertThat(requests.getLast().body().path("target_lang").asString()).isEqualTo(deepl[i]);
        }
    }
}
