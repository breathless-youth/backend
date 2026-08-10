package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

class SlackWebhookNotifierTest {

    // 자격증명처럼 취급해야 하는 조각 — 실패 시 예외 메시지·원인 체인 어디에도 나타나면 안 된다
    private static final String SECRET_TOKEN = "T00000000/B00000000/verySecretSlackToken1234567890";
    // 아무도 리스닝하지 않는 로컬 포트 — 외부 네트워크·DNS 없이 즉시 connection refused를 재현한다
    private static final String UNREACHABLE_WEBHOOK_URL = "http://127.0.0.1:1/services/" + SECRET_TOKEN;

    private SlackWebhookNotifier notifier(String webhookUrl) {
        return new SlackWebhookNotifier(RestClient.builder(), webhookUrl);
    }

    @Test
    void webhook_URL이_있으면_활성화된다() {
        assertThat(notifier("https://hooks.slack.test/services/T/B/X").isEnabled())
                .isTrue();
    }

    @Test
    void webhook_URL이_비어있으면_비활성이다() {
        assertThat(notifier("").isEnabled()).isFalse();
    }

    @Test
    void webhook_URL이_공백뿐이어도_비활성이다() {
        assertThat(notifier("   ").isEnabled()).isFalse();
    }

    @Test
    void webhook_URL_형식이_깨져있으면_비활성이다() {
        // SSM 파라미터에 URL을 붙여넣다 escape가 깨지는 경우(예: percent-escape 오타)를 재현한다.
        // 이런 URL은 RestClient의 .uri() 호출이 던지는 IllegalArgumentException을 기동 시점에
        // 미리 걸러내야 한다 — 그러지 않으면 첫 발송(오전 10시)에야 실패가 드러난다.
        assertThat(notifier("http://hooks.slack.test/services/%zz/" + SECRET_TOKEN)
                        .isEnabled())
                .isFalse();
    }

    @Test
    void webhook_URL_형식이_깨져있어도_기동_WARN_로그에_시크릿_토큰이_남지_않는다() {
        String malformedUrl = "http://hooks.slack.test/services/%zz/" + SECRET_TOKEN;

        Logger logger = (Logger) LoggerFactory.getLogger(SlackWebhookNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            notifier(malformedUrl);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(msg -> msg.contains(SECRET_TOKEN))
                .noneMatch(msg -> msg.contains(malformedUrl));
    }

    @Test
    void webhook_URL_형식이_깨져있으면_비활성이라_발송해도_예외를_던지지_않는다() {
        // resolveWebhookUrl이 malformed URL을 빈 문자열로 대체하므로, isEnabled()가 false가
        // 되어 send()는 .uri()를 호출조차 하지 않고 조용히 넘어간다.
        assertThatCode(() -> notifier("http://hooks.slack.test/services/%zz/" + SECRET_TOKEN)
                        .send("무시되는 메시지"))
                .doesNotThrowAnyException();
    }

    @Test
    void 비활성_상태에서_발송해도_예외를_던지지_않는다() {
        // 설정 누락이 예외로 번지면 스케줄러가 매일 실패 알림을 만든다 — 조용히 넘긴다
        assertThatCode(() -> notifier("").send("무시되는 메시지")).doesNotThrowAnyException();
    }

    @Test
    void 발송_실패시_SlackNotificationException으로_감싼다() {
        assertThatThrownBy(() -> notifier(UNREACHABLE_WEBHOOK_URL).send("메시지"))
                .isInstanceOf(SlackNotificationException.class);
    }

    @Test
    void 발송_실패_예외에_webhook_URL이_어디에도_남지_않는다() {
        // Webhook URL은 그 자체가 자격증명이다 — I/O 실패 시 던지는 예외(원본은
        // ResourceAccessException)의 메시지에 전체 URL이 그대로 들어가면 로그(CloudWatch)와
        // Sentry로 유출된다. 메시지뿐 아니라 원인 체인 전체를 훑어 URL 조각이 없는지 확인한다.
        Throwable thrown =
                catchThrowable(() -> notifier(UNREACHABLE_WEBHOOK_URL).send("메시지"));

        assertThat(thrown).isNotNull();
        List<String> allDiagnosticText = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            allDiagnosticText.add(String.valueOf(t.getMessage()));
            allDiagnosticText.add(t.toString());
        }

        assertThat(allDiagnosticText)
                .noneMatch(text -> text.contains(SECRET_TOKEN))
                .noneMatch(text -> text.contains(UNREACHABLE_WEBHOOK_URL))
                .noneMatch(text -> text.contains("127.0.0.1"));
        // 원인을 아예 붙이지 않는 것이 URL 미유출을 보장하는 가장 확실한 방법이다
        assertThat(thrown.getCause()).isNull();
    }

    // @Retryable은 Spring 컨테이너가 만든 프록시를 통해 호출될 때만 동작한다(SlackNotifier
    // 인터페이스의 send() 주석 참고) — 그래서 아래 두 테스트만 new로 직접 생성하는 대신 최소 스프링
    // 컨텍스트를 띄워 실제 재시도 프록시를 거치게 한다.
    @Test
    void 처음_두_번_실패해도_세_번째_시도에서_성공하면_예외를_던지지_않는다() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = stubServer(requestCount, (exchange, count) -> {
            int status = count <= 2 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
        });
        try {
            server.start();
            String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/services/x";

            try (AnnotationConfigApplicationContext context = retryingContext(webhookUrl)) {
                SlackNotifier notifier = context.getBean(SlackNotifier.class);
                assertThatCode(() -> notifier.send("메시지")).doesNotThrowAnyException();
            }
            assertThat(requestCount.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 세_번_모두_실패하면_더_이상_재시도하지_않고_예외를_던진다() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = stubServer(requestCount, (exchange, count) -> exchange.sendResponseHeaders(500, -1));
        try {
            server.start();
            String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/services/x";

            try (AnnotationConfigApplicationContext context = retryingContext(webhookUrl)) {
                SlackNotifier notifier = context.getBean(SlackNotifier.class);
                assertThatThrownBy(() -> notifier.send("메시지")).isInstanceOf(SlackNotificationException.class);
            }
            assertThat(requestCount.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private AnnotationConfigApplicationContext retryingContext(String webhookUrl) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RetryEnabledConfig.class);
        context.registerBean(
                SlackWebhookNotifier.class, () -> new SlackWebhookNotifier(RestClient.builder(), webhookUrl));
        context.refresh();
        return context;
    }

    @EnableResilientMethods
    @Configuration(proxyBeanMethods = false)
    private static class RetryEnabledConfig {}

    private HttpServer stubServer(AtomicInteger requestCount, ResponseHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange, requestCount.incrementAndGet());
            } finally {
                exchange.close();
            }
        });
        return server;
    }

    @FunctionalInterface
    private interface ResponseHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, int requestCount) throws IOException;
    }
}
