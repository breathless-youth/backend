package project.study.metrics.slack;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Slack 봇(`chat.postMessage`) 발송기 — Incoming Webhook은 스레드를 못 달아 바꿨다(ADR-0030 결정 8). 봇 권한은 `chat:write`, 채널 초대가 필요하다.
 *
 * <p>봇 토큰은 자격증명이라 예외 메시지·로그 어디에도 남기지 않고 원인 예외도 붙이지 않는다(SlackNotificationException 참고).
 */
@Slf4j
@Component
public class SlackBotNotifier implements SlackNotifier {

    // 응답이 안 오는 연결에 지표 스레드가 묶이지 않게 (옛 웹훅 발송기와 같은 값)
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_RETRY_AFTER_SEC = 30;
    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;
    private final String token;
    private final String channel;

    private record SlackReply(boolean ok, String ts, String error, long retryAfterSec) {
        static SlackReply rateLimited(long retryAfterSec) {
            return new SlackReply(false, null, null, retryAfterSec);
        }

        boolean limited() {
            return retryAfterSec >= 0;
        }
    }

    public SlackBotNotifier(
            RestClient.Builder restClientBuilder,
            @Value("${metrics.slack.base-url:https://slack.com}") String baseUrl,
            @Value("${metrics.slack.bot-token:}") String token,
            @Value("${metrics.slack.channel-id:}") String channel) {
        // 공유 싱글턴 빌더를 clone()해 이 발송기 전용 타임아웃만 얹는다 — 원본을 건드리면 전역 동작이 바뀐다
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.token = token == null ? "" : token.strip();
        this.channel = channel == null ? "" : channel.strip();
        if (!isEnabled()) {
            log.warn("metrics.slack.bot-token·channel-id 중 빈 값이 있어 일일 지표 리포트를 보내지 않는다");
        }
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(token) && StringUtils.hasText(channel);
    }

    @Override
    public String post(String text) {
        String ts = call(body(text, null));
        if (!StringUtils.hasText(ts)) {
            throw new SlackNotificationException("Slack API 응답에 ts가 없다");
        }
        return ts;
    }

    @Override
    public void reply(String threadTs, String text) {
        call(body(text, threadTs));
    }

    private Map<String, Object> body(String text, String threadTs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("text", text);
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        return body;
    }

    // 429는 Retry-After초 뒤 한 번 더 — 그래도 막히면 @Retryable이 짧은 간격으로 두 번 더 시도한다
    private String call(Map<String, Object> body) {
        if (!isEnabled()) {
            throw new SlackNotificationException("Slack 봇 설정이 없다");
        }
        SlackReply reply = exchange(body);
        if (reply.limited()) {
            sleep(reply.retryAfterSec());
            reply = exchange(body);
        }
        if (reply.limited()) {
            throw new SlackNotificationException("Slack 요청 한도 초과 (HTTP 429)");
        }
        if (!reply.ok()) {
            throw new SlackNotificationException("Slack API 오류 (%s)".formatted(reply.error()));
        }
        return reply.ts();
    }

    private SlackReply exchange(Map<String, Object> body) {
        try {
            return restClient
                    .post()
                    .uri("/api/chat.postMessage")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> read(response));
        } catch (RestClientException e) {
            throw new SlackNotificationException(
                    "Slack API 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        }
    }

    private static SlackReply read(ConvertibleClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        if (status == TOO_MANY_REQUESTS) {
            return SlackReply.rateLimited(retryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (status < 200 || status >= 300) {
            throw new SlackNotificationException("Slack API 응답 실패 (HTTP %d)".formatted(status));
        }
        JsonNode json = response.bodyTo(JsonNode.class);
        if (json == null) {
            throw new SlackNotificationException("Slack API 응답 본문이 비었다");
        }
        return new SlackReply(json.path("ok").asBoolean(false), text(json, "ts"), text(json, "error"), -1);
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static long retryAfter(String header) {
        try {
            return Math.clamp(Long.parseLong(header == null ? "1" : header.strip()), 0, MAX_RETRY_AFTER_SEC);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlackNotificationException("Slack 재시도 대기 중 중단됐다");
        }
    }
}
