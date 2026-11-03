package project.study.user.oauth;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 소셜 프로바이더 호출(구글·카카오 tokeninfo, 애플 JWKS·토큰 교환·해지)에 쓰는 RestClient. 공유 빌더에는 타임아웃이 없다 — yaml의
 * {@code spring.http.client.*}는 이 빌더에 닿지 않는다. 그대로 쓰면 프로바이더가 응답하지 않을 때 link·탈퇴 요청 스레드가 무한히 묶이므로,
 * SlackBotNotifier·HttpAmplitudeExportSource처럼 빌더를 clone()해 이 용도 전용 타임아웃을 얹는다.
 *
 * <p>연결 5초는 두 클래스와 같은 값이다. 읽기 5초는 사람이 기다리는 요청이라 yaml이 의도한 값({@code read-timeout: 5s})을 따른다 — 지표
 * 스레드용인 Slack의 10초보다 짧다. JDK 클라이언트의 읽기 타임아웃은 요청 시작부터의 전체 마감이다.
 */
@Configuration(proxyBeanMethods = false)
public class SocialProviderHttpConfig {

    /** 검증기·해지기가 {@code @Qualifier}로 받는 빈 이름. */
    public static final String REST_CLIENT = "socialProviderRestClient";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean(REST_CLIENT)
    RestClient socialProviderRestClient(RestClient.Builder restClientBuilder) {
        return create(restClientBuilder, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    // 공유 싱글턴 빌더를 clone()한다 — 원본에 requestFactory를 얹으면 전역 동작이 바뀐다
    static RestClient create(RestClient.Builder restClientBuilder, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return restClientBuilder.clone().requestFactory(requestFactory).build();
    }
}
