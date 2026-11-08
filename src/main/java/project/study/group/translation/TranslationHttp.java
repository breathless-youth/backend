package project.study.group.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 본문/인증정보/공급자 예외를 기록하지 않는 제한 시간 HTTP 경계. */
final class TranslationHttp {
    record Wire(int status, JsonNode body, Instant retryAt) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final URI base;

    TranslationHttp(URI base) {
        this.base = base;
    }

    Wire call(String path, Object body, Map<String, String> headers, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) return new Wire(0, null, null);
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            headers.forEach(builder::header);
            if (body == null) builder.GET();
            else builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            pending = CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = pending.get(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS);
            JsonNode parsed = null;
            try {
                parsed = JSON.readTree(response.body());
            } catch (RuntimeException ignored) {
                /* invalid payload */
            }
            return new Wire(
                    response.statusCode(),
                    parsed,
                    retryAt(response.headers().firstValue("Retry-After").orElse(null)));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return new Wire(0, null, null);
        } catch (Exception ignored) {
            return new Wire(0, null, null);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    static TextTranslator.TranslationReply failure(Wire wire, TranslationProvider provider) {
        var code = TextTranslator.Failure.TEMPORARY;
        if (wire.status() == 200) code = TextTranslator.Failure.INVALID_RESPONSE;
        else if (wire.status() == 429) code = TextTranslator.Failure.RATE_LIMIT;
        else if (wire.status() == 456 || quota(wire, provider)) code = TextTranslator.Failure.QUOTA;
        else if (wire.status() == 401 || wire.status() == 403) code = TextTranslator.Failure.AUTH;
        else if (wire.status() == 400) code = TextTranslator.Failure.UNSUPPORTED;
        return new TextTranslator.TranslationReply(List.of(), code, wire.retryAt());
    }

    private static boolean quota(Wire wire, TranslationProvider provider) {
        if (wire.body() == null || wire.status() != 403) return false;
        JsonNode error = wire.body().path("error");
        if (provider == TranslationProvider.AZURE) return error.path("code").asInt() == 403001;
        if (provider != TranslationProvider.GOOGLE) return false;
        if (error.path("message").asString("").contains("Daily Limit Exceeded")) return true;
        for (JsonNode item : error.path("errors")) {
            if (List.of("dailyLimitExceeded", "quotaExceeded")
                    .contains(item.path("reason").asString(""))) return true;
        }
        return false;
    }

    static TextTranslator.TranslationReply translated(
            Wire wire, JsonNode entries, int count, String field, boolean unescape) {
        if (wire.status() != 200 || entries == null || !entries.isArray() || entries.size() != count)
            return TextTranslator.TranslationReply.failed(TextTranslator.Failure.INVALID_RESPONSE);
        var texts = new java.util.ArrayList<String>();
        for (JsonNode entry : entries) {
            JsonNode text = entry.path(field);
            if (!text.isString() || text.asString().isBlank())
                return TextTranslator.TranslationReply.failed(TextTranslator.Failure.INVALID_RESPONSE);
            texts.add(unescape ? HtmlUtils.htmlUnescape(text.asString()) : text.asString());
        }
        return new TextTranslator.TranslationReply(List.copyOf(texts), TextTranslator.Failure.NONE, null);
    }

    static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private static Instant retryAt(String header) {
        if (header == null) return null;
        try {
            return Instant.now().plusSeconds(Math.max(1, Long.parseLong(header)));
        } catch (RuntimeException ignored) {
            try {
                return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
