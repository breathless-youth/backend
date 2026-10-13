package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.metrics.amplitude.ExportZipFixture.event;
import static project.study.metrics.amplitude.ExportZipFixture.zip;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpAmplitudeExportSourceTest {

    private static final String API_KEY = "apiKey123";
    private static final String SECRET_KEY = "verySecretAmplitudeKey987";
    private static final ExportWindow WINDOW = new ExportWindow(LocalDate.of(2026, 10, 12));
    private static final byte[] ONE_EVENT_ZIP =
            zip(Map.of("a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000"))));

    private HttpServer server;
    private final AtomicReference<URI> requestUri = new AtomicReference<>();
    private final AtomicReference<String> requestAuthorization = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpAmplitudeExportSource source(String apiKey, String secretKey, long maxBytes) {
        return source(apiKey, secretKey, maxBytes, Duration.ofSeconds(10));
    }

    private HttpAmplitudeExportSource source(String apiKey, String secretKey, long maxBytes, Duration timeout) {
        // 서버를 띄우지 않은 테스트는 아무도 리스닝하지 않는 포트로 connection refused를 재현한다
        String baseUrl = server == null
                ? "http://127.0.0.1:1"
                : "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpAmplitudeExportSource(RestClient.builder(), baseUrl, apiKey, secretKey, maxBytes, timeout);
    }

    private void respondAfter(Duration delay) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delay.toMillis());
                exchange.sendResponseHeaders(200, ONE_EVENT_ZIP.length);
                exchange.getResponseBody().write(ONE_EVENT_ZIP);
            } catch (InterruptedException | IOException e) {
                // 클라이언트가 먼저 포기했다
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void respond(int status, byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestUri.set(exchange.getRequestURI());
                requestAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @Test
    void 창의_수신_시각_범위와_Basic_인증으로_요청해_파싱한다() throws IOException {
        respond(200, ONE_EVENT_ZIP);

        WindowAggregate result = source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW);

        assertThat(requestUri.get().getPath()).isEqualTo("/api/2/export");
        assertThat(requestUri.get().getQuery()).isEqualTo("start=20261011T15&end=20261012T14");
        String expectedAuthorization = "Basic "
                + Base64.getEncoder().encodeToString((API_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(requestAuthorization.get()).isEqualTo(expectedAuthorization);
        assertThat(result.eventCount()).isEqualTo(1);
    }

    @Test
    void 데이터가_없다는_404면_빈_결과다() throws IOException {
        respond(404, new byte[0]);

        WindowAggregate result = source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW);

        assertThat(result.eventCount()).isZero();
        assertThat(result.counts()).isEmpty();
    }

    @Test
    void 서버_오류면_예외이고_메시지에_시크릿이_없다() throws IOException {
        respond(500, "boom".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW))
                .isInstanceOf(AmplitudeExportException.class)
                .hasMessageContaining("500")
                .hasNoCause()
                .satisfies(e ->
                        assertThat(e.getMessage()).doesNotContain(SECRET_KEY).doesNotContain(API_KEY));
    }

    @Test
    void 연결이_안_되면_예외이고_원인을_붙이지_않는다() {
        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW))
                .isInstanceOf(AmplitudeExportException.class)
                .hasNoCause();
    }

    @Test
    void 응답이_크기_상한을_넘으면_중단한다() throws IOException {
        respond(200, ONE_EVENT_ZIP);

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 10).fetch(WINDOW))
                .isInstanceOf(ExportTooLargeException.class);
    }

    @Test
    void 크기_상한을_넘으면_남은_응답을_더_받지_않는다() throws Exception {
        long total = 64L * 1024 * 1024;
        AtomicLong written = new AtomicLong();
        CountDownLatch handlerDone = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, total);
                OutputStream body = exchange.getResponseBody();
                byte[] chunk = new byte[64 * 1024];
                while (written.get() < total) {
                    body.write(chunk);
                    written.addAndGet(chunk.length);
                }
            } catch (IOException e) {
                // 클라이언트가 연결을 끊었다 — 기대한 결과
            } finally {
                exchange.close();
                handlerDone.countDown();
            }
        });
        server.start();

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1024).fetch(WINDOW))
                .isInstanceOf(ExportTooLargeException.class);

        assertThat(handlerDone.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(written.get()).isLessThan(total);
    }

    @Test
    void 다운로드_마감을_넘기면_예외다() throws IOException {
        respondAfter(Duration.ofMillis(1500));

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1_000_000, Duration.ofMillis(300))
                        .fetch(WINDOW))
                .isInstanceOf(AmplitudeExportException.class)
                .hasNoCause();
    }

    @Test
    void 다운로드_마감은_설정값을_따른다() throws IOException {
        respondAfter(Duration.ofMillis(500));

        WindowAggregate result =
                source(API_KEY, SECRET_KEY, 1_000_000, Duration.ofSeconds(5)).fetch(WINDOW);

        assertThat(result.eventCount()).isEqualTo(1);
    }

    @Test
    void 키가_하나라도_비면_비활성이다() {
        assertThat(source(API_KEY, SECRET_KEY, 1).isEnabled()).isTrue();
        assertThat(source("", SECRET_KEY, 1).isEnabled()).isFalse();
        assertThat(source(API_KEY, "  ", 1).isEnabled()).isFalse();
    }
}
