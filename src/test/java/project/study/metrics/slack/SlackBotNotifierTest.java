package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

class SlackBotNotifierTest {

    // 자격증명 — 실패 메시지·원인 어디에도 나타나면 안 된다
    private static final String TOKEN = "xoxb-0000-verySecretBotToken";
    private static final String CHANNEL = "C0123456";

    private record Reply(int status, String retryAfter, String body) {}

    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat.postMessage", exchange -> {
            try (exchange) {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Reply reply = replies.isEmpty() ? new Reply(500, null, "") : replies.poll();
                if (reply.retryAfter() != null) {
                    exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
                }
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SlackBotNotifier notifier() {
        return new SlackBotNotifier(RestClient.builder(), baseUrl(), TOKEN, CHANNEL);
    }

    private void reply(int status, String body) {
        replies.add(new Reply(status, null, body));
    }

    @Test
    void 토큰과_채널이_모두_있어야_켜진다() {
        assertThat(notifier().isEnabled()).isTrue();
        assertThat(new SlackBotNotifier(RestClient.builder(), baseUrl(), " ", CHANNEL).isEnabled())
                .isFalse();
        assertThat(new SlackBotNotifier(RestClient.builder(), baseUrl(), TOKEN, "").isEnabled())
                .isFalse();
    }

    @Test
    void 본_메시지를_봇_토큰으로_보내고_ts를_돌려준다() {
        reply(200, "{\"ok\":true,\"channel\":\"C0123456\",\"ts\":\"1700000000.000100\"}");

        assertThat(notifier().post("📊 요약")).isEqualTo("1700000000.000100");
        assertThat(authorizations).containsExactly("Bearer " + TOKEN);
        assertThat(bodies.getFirst())
                .contains(
                        "\"channel\":\"C0123456\"",
                        "\"text\":\"📊 요약\"",
                        "\"unfurl_links\":false",
                        "\"unfurl_media\":false")
                .doesNotContain("thread_ts");
    }

    @Test
    void 스레드_댓글은_본_메시지_ts에_단다() {
        reply(200, "{\"ok\":true,\"ts\":\"1700000000.000200\"}");

        notifier().reply("1700000000.000100", "🕐 가입");

        assertThat(bodies.getFirst()).contains("\"thread_ts\":\"1700000000.000100\"");
    }

    @Test
    void ok가_false면_Slack_오류_코드로_실패한다() {
        reply(200, "{\"ok\":false,\"error\":\"not_in_channel\"}");

        assertThatThrownBy(() -> notifier().post("x"))
                .isInstanceOf(SlackNotificationException.class)
                .hasMessageContaining("not_in_channel")
                .hasNoCause();
    }

    @Test
    void ts가_없는_성공_응답은_실패로_본다() {
        reply(200, "{\"ok\":true}");

        // ts 없이 스레드를 달면 댓글이 채널 본문에 흩어진다
        assertThatThrownBy(() -> notifier().post("x")).isInstanceOf(SlackNotificationException.class);
    }

    @Test
    void 실패_메시지에_토큰을_남기지_않는다() {
        reply(500, "");

        Throwable serverError = catchThrowable(() -> notifier().post("x"));
        Throwable refused = catchThrowable(
                () -> new SlackBotNotifier(RestClient.builder(), "http://127.0.0.1:1", TOKEN, CHANNEL).post("x"));

        assertThat(serverError)
                .isInstanceOf(SlackNotificationException.class)
                .hasMessageContaining("HTTP 500")
                .hasNoCause();
        assertThat(serverError.getMessage()).doesNotContain(TOKEN);
        assertThat(refused).isInstanceOf(SlackNotificationException.class).hasNoCause();
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    void 요청_한도에_걸리면_Retry_After만큼_기다렸다_한_번_더_보낸다() {
        replies.add(new Reply(429, "0", ""));
        reply(200, "{\"ok\":true,\"ts\":\"1.2\"}");

        assertThat(notifier().post("x")).isEqualTo("1.2");
        assertThat(bodies).hasSize(2);
    }

    @Test
    void 요청_한도가_이어지면_실패한다() {
        replies.add(new Reply(429, "0", ""));
        replies.add(new Reply(429, "0", ""));

        assertThatThrownBy(() -> notifier().post("x"))
                .isInstanceOf(SlackNotificationException.class)
                .hasMessageContaining("429");
    }

    @Test
    void 재시도_프록시를_거치면_세_번까지_보낸다() {
        reply(500, "");
        reply(500, "");
        reply(200, "{\"ok\":true,\"ts\":\"9.9\"}");

        try (AnnotationConfigApplicationContext context = retryingContext()) {
            assertThat(context.getBean(SlackNotifier.class).post("x")).isEqualTo("9.9");
        }
        assertThat(bodies).hasSize(3);
    }

    // @Retryable은 컨테이너가 만든 프록시를 거쳐야 동작한다
    private AnnotationConfigApplicationContext retryingContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RetryEnabledConfig.class);
        context.registerBean(SlackBotNotifier.class, this::notifier);
        context.refresh();
        return context;
    }

    @EnableResilientMethods
    @Configuration(proxyBeanMethods = false)
    private static class RetryEnabledConfig {}
}
