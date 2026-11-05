package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 프로바이더가 응답하지 않아도 요청 스레드가 무한히 묶이지 않는다 — 읽기 타임아웃이 실제로 걸리는지 느린 로컬 서버로 본다. */
class SocialProviderHttpConfigTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 응답이_늦으면_읽기_타임아웃에서_끊는다() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        RestClient client =
                SocialProviderHttpConfig.create(RestClient.builder(), Duration.ofSeconds(1), Duration.ofMillis(300));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri(url).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void 운영_값은_연결_5초_읽기_5초다() {
        assertThat(SocialProviderHttpConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(SocialProviderHttpConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    }
}
