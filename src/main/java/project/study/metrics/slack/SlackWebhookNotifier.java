package project.study.metrics.slack;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Slack Incoming Webhook 발송기.
 */
@Slf4j
@Component
public class SlackWebhookNotifier implements SlackNotifier {

    // 커넥션은 붙었는데 응답이 안 오는 상태(NAT 타임아웃, LB half-open 등)에서 스케줄러 스레드가
    // 무한 대기하는 것을 막는다. Spring 스케줄러 풀 크기 기본값이 1이라, 한 번 막히면 그 이후
    // 모든 스케줄 작업이 다시는 실행되지 않는다 — 예외가 안 나므로 catch도 Sentry도 걸리지 않는
    // 완전 무증상 장애가 된다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackWebhookNotifier(
            RestClient.Builder restClientBuilder, @Value("${metrics.slack.webhook-url:}") String webhookUrl) {
        // StudyApplication의 RestClient.Builder는 다른 용도(향후 Google 토큰 검증 등)와 공유하는
        // 싱글턴 빈이다. clone()으로 독립 사본을 떠서 이 발송기 전용 타임아웃만 얹는다 — 원본 빈을
        // 직접 건드리면 전역 동작이 바뀐다.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient =
                restClientBuilder.clone().requestFactory(requestFactory).build();
        this.webhookUrl = resolveWebhookUrl(webhookUrl);
        if (!isEnabled()) {
            log.warn("metrics.slack.webhook-url이 비어 있거나 형식이 올바르지 않아 일일 지표 리포트를 발송하지 않는다");
        }
    }

    // 발송 시점(RestClient의 .uri() 호출)에 처음 파싱하면, 형식이 깨진 URL(SSM 값 붙여넣기 중
    // 섞인 공백, 깨진 percent-escape 등)이 던지는 IllegalArgumentException이 기동 후 첫 발송(오전
    // 10시)에야 드러난다. RestClient가 내부적으로 쓰는 것과 동일한 파서(UriComponentsBuilder의
    // 기본 RFC 파서, DefaultUriBuilderFactory#uriString 참고)로 기동 시점에 미리 검증해서, 형식이
    // 깨진 URL도 빈 문자열과 동일하게 "비활성" 취급한다. 검증 실패로 애플리케이션 기동 자체를 막지
    // 않으며, URL 자체가 자격증명이므로 예외 메시지·WARN 로그 어디에도 URL을 찍지 않는다.
    private static String resolveWebhookUrl(String webhookUrl) {
        if (!StringUtils.hasText(webhookUrl)) {
            return webhookUrl;
        }
        try {
            UriComponentsBuilder.fromUriString(webhookUrl);
            return webhookUrl;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(webhookUrl);
    }

    @Override
    public void send(String message) {
        if (!isEnabled()) {
            return;
        }
        try {
            restClient
                    .post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new SlackNotificationException(
                    "Slack webhook 응답 실패 (HTTP %d)".formatted(e.getStatusCode().value()));
        } catch (RestClientException e) {
            throw new SlackNotificationException(
                    "Slack webhook 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        } catch (IllegalArgumentException e) {
            // 기동 시 검증(resolveWebhookUrl)을 통과했더라도 최후의 방어선을 둔다 — RestClient의
            // URI 파서가 던지는 IllegalArgumentException(예: InvalidUrlException)은
            // RestClientException의 하위 타입이 아니라 위 catch들을 통과하며, 예외 메시지에 URL이
            // 담길 수 있으므로 원본을 그대로 던지거나 cause로 붙이지 않는다.
            throw new SlackNotificationException("Slack webhook URL 형식이 올바르지 않다");
        }
    }
}
