package project.study.metrics.slack;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
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
    // 한 번 기다림의 상한 — 지표 스레드를 몇 분 넘게 붙잡지 않게. Slack은 보통 몇 초를 준다
    private static final long MAX_RETRY_AFTER_SEC = 120;
    private static final int MAX_RATE_LIMIT_WAITS = 3;
    private static final int TOO_MANY_REQUESTS = 429;
    // 토큰·채널 ID는 보이는 ASCII뿐이다 — 개행 등이 섞이면 JDK가 헤더 값 째로 예외 메시지에 넣어 토큰이 새므로 미리 거른다
    private static final Pattern CREDENTIAL = Pattern.compile("[\\x21-\\x7E]+");

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
        this.token = credential(token);
        this.channel = credential(channel);
        if (!isEnabled()) {
            log.warn("metrics.slack.bot-token·channel-id 중 비었거나 형식이 잘못된 값이 있어 일일 지표 리포트를 보내지 않는다");
        }
    }

    // 값은 로그에 남기지 않는다 — 형식이 틀리면 빈 값과 같이 비활성으로 본다
    private static String credential(String value) {
        String stripped = value == null ? "" : value.strip();
        return CREDENTIAL.matcher(stripped).matches() ? stripped : "";
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(token) && StringUtils.hasText(channel);
    }

    @Override
    public String post(SlackMessage message) {
        String ts = call(body(message, null));
        if (!StringUtils.hasText(ts)) {
            throw new SlackNotificationException("Slack API 응답에 ts가 없다");
        }
        return ts;
    }

    @Override
    public void reply(String threadTs, SlackMessage message) {
        call(body(message, threadTs));
    }

    // 보이는 건 blocks다. text는 알림·검색에 쓰이는 대체 글이라 mrkdwn으로 해석되므로 이스케이프한다
    private Map<String, Object> body(SlackMessage message, String threadTs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("text", escape(message.text()));
        body.put("blocks", SlackBlocks.of(message));
        // text 속 URL도 자동 링크로 만들지 않는다 — 목표 같은 사용자 입력은 글자 그대로
        body.put("parse", "none");
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        return body;
    }

    // 429는 응답마다 Retry-After초를 기다렸다 다시 보낸다(최대 3번). 그래도 막히면 @Retryable에서 빠지는 예외로 끝낸다
    private String call(Map<String, Object> body) {
        if (!isEnabled()) {
            throw new SlackNotificationException("Slack 봇 설정이 없다");
        }
        SlackReply reply = exchange(body);
        for (int waits = 0; reply.limited() && waits < MAX_RATE_LIMIT_WAITS; waits++) {
            sleep(reply.retryAfterSec());
            reply = exchange(body);
        }
        if (reply.limited()) {
            throw new SlackRateLimitException(
                    "Slack 요청 한도 초과 (HTTP 429, %d번 기다려도 풀리지 않음)".formatted(MAX_RATE_LIMIT_WAITS));
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
        } catch (RestClientException | IllegalArgumentException e) {
            // 원인 예외 메시지에 요청 헤더(토큰)가 들어갈 수 있어 타입 이름만 남긴다
            throw new SlackNotificationException(
                    "Slack API 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        }
    }

    /** 대체 글의 Slack 제어 문자(`&`·`<`·`>`)를 이스케이프한다 — 목표·UTM 같은 사용자 입력이 멘션(`<!channel>`)이나 위장 링크가 되지 않게. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
