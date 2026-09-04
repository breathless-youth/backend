package project.study.room.turn;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cloudflare TURN API용 RestClient (BY-587). 외부 API 지연이 방 입장 지연으로 번지지 않게 짧은 타임아웃을 걸고,
 * Bearer 토큰·base URL을 여기서 고정한다. 제공자는 완성된 클라이언트만 받아 단위테스트에서 mock 서버로 교체 가능하다.
 */
@Configuration
public class CloudflareTurnConfig {

    public static final String REST_CLIENT = "cloudflareTurnRestClient";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Bean(REST_CLIENT)
    public RestClient cloudflareTurnRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.room.turn.cloudflare.api-token:}") String apiToken,
            @Value("${app.room.turn.cloudflare.base-url:https://rtc.live.cloudflare.com}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .requestFactory(factory)
                .build();
    }
}
