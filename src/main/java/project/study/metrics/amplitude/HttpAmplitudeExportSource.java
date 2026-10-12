package project.study.metrics.amplitude;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Amplitude Export API({@code GET /api/2/export}) 구현. 응답 zip을 받는 동안 줄 단위로 파싱해 메모리에 원본을 올리지 않는다.
 */
@Slf4j
@Component
public class HttpAmplitudeExportSource implements AmplitudeExportSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int NOT_FOUND = 404;

    private final RestClient restClient;
    private final String baseUrl;
    private final String authorization;
    private final long maxDownloadBytes;

    public HttpAmplitudeExportSource(
            RestClient.Builder restClientBuilder,
            @Value("${metrics.amplitude.base-url:https://amplitude.com}") String baseUrl,
            @Value("${metrics.amplitude.api-key:}") String apiKey,
            @Value("${metrics.amplitude.secret-key:}") String secretKey,
            @Value("${metrics.amplitude.max-download-bytes:209715200}") long maxDownloadBytes,
            @Value("${metrics.amplitude.download-timeout:PT10M}") Duration downloadTimeout) {
        // 공유 싱글턴 빌더를 clone()해 이 소스 전용 타임아웃만 얹는다 (SlackWebhookNotifier와 같은 이유).
        // readTimeout은 "응답이 멈춘 시간"이 아니라 요청 시작부터의 전체 마감이다 — Spring 7 JdkClientHttpRequest는
        // 마감이 지나면 받는 중이어도 본문을 닫는다. 첫 바이트(Amplitude가 zip을 조립하는 시간) + 전송 + 파싱이 모두
        // 들어가므로 넉넉히 둔다. 전용 스레드라 길어도 다른 작업을 막지 않고, 무한 대기만 막으면 된다
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(downloadTimeout);
        this.restClient =
                restClientBuilder.clone().requestFactory(requestFactory).build();
        this.baseUrl = baseUrl;
        this.authorization = basic(apiKey, secretKey);
        this.maxDownloadBytes = maxDownloadBytes;
        if (!isEnabled()) {
            log.warn("metrics.amplitude.api-key/secret-key가 비어 있어 Amplitude 적재를 하지 않는다");
        }
    }

    private static String basic(String apiKey, String secretKey) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(secretKey)) {
            return null;
        }
        String credentials = apiKey.trim() + ":" + secretKey.trim();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isEnabled() {
        return authorization != null;
    }

    @Override
    public WindowAggregate fetch(ExportWindow window) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/2/export")
                .queryParam("start", window.startParam())
                .queryParam("end", window.endParam())
                .build()
                .toUri();
        try {
            return restClient
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange((request, response) -> read(window, response));
        } catch (RestClientException e) {
            // 함수 안에서 난 IOException(깨진 zip 등)도 RestClient가 ResourceAccessException으로 감싸 여기로 온다
            throw new AmplitudeExportException(
                    "Amplitude Export 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        }
    }

    private WindowAggregate read(ExportWindow window, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == NOT_FOUND) {
            // Export는 그 기간에 데이터가 없으면 404를 준다 — 0건으로 처리 기록한다 (스펙 §10). prod는 하루 수천 건이라
            // 404는 설정 오류(프로젝트·리전)일 수도 있어 알린다. 실패로 두면 정말 빈 날이 매일 재시도되며 최신 창까지 막는다
            log.warn("Amplitude Export가 404를 줘 창을 0건으로 기록한다 (창={})", window.date());
            Sentry.captureMessage(
                    "Amplitude Export 404 — 창을 0건으로 기록 (창=%s)".formatted(window.date()), SentryLevel.WARNING);
            return WindowAggregate.empty();
        }
        if (!status.is2xxSuccessful()) {
            throw new AmplitudeExportException("Amplitude Export 응답 실패 (HTTP %d)".formatted(status.value()));
        }
        long startedAt = System.nanoTime();
        // 본문을 직접 닫는다 — 상한 초과로 중간에 던지면 응답 close가 남은 본문을 끝까지 비워(drain) 다운로드가 계속된다.
        // 먼저 닫아 두면 연결이 끊겨 그 자리에서 멈춘다
        try (InputStream body = response.getBody()) {
            LimitedInputStream limited = new LimitedInputStream(body, maxDownloadBytes);
            WindowAggregate aggregate = AmplitudeEventParser.parse(limited);
            // 크기·시간 추세가 배치 분리 재검토 신호다 (ADR-0030 결정 7)
            log.info(
                    "Amplitude Export 창 적재 준비 (창={}, {}바이트, {}ms, 적재={}, 건너뜀={})",
                    window.date(),
                    limited.readBytes(),
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    aggregate.eventCount(),
                    aggregate.skipped());
            return aggregate;
        }
    }
}
