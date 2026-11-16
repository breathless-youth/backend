package project.study.user.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * App Store 조회 API(iTunes lookup)에서 출시된 버전을 읽는다 (BY-1312). 인증이 필요 없는 공개 API다. 공유 빌더에는 타임아웃이 없어
 * clone()해 얹는다(HttpAdMobKeySource와 같은 이유).
 */
@Component
public class HttpAppStoreVersionSource implements AppStoreVersionSource {

    private static final String LOOKUP_URL = "https://itunes.apple.com/lookup?id={id}&country={country}";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String appStoreId;
    private final String country;

    public HttpAppStoreVersionSource(
            RestClient.Builder restClientBuilder,
            @Value("${app.review-login.release-sync.app-store-id:}") String appStoreId,
            @Value("${app.review-login.release-sync.country:kr}") String country) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient =
                restClientBuilder.clone().requestFactory(requestFactory).build();
        this.appStoreId = appStoreId.trim();
        this.country = country.trim();
    }

    @Override
    public Optional<String> releasedVersion() {
        if (appStoreId.isEmpty()) {
            return Optional.empty();
        }
        Lookup body =
                restClient.get().uri(LOOKUP_URL, appStoreId, country).retrieve().body(Lookup.class);
        if (body == null || body.results() == null || body.results().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(body.results().getFirst().version());
    }

    record Lookup(List<Result> results) {}

    record Result(String version) {}
}
