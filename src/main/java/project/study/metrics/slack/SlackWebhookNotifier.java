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

/**
 * Slack Incoming Webhook 발송기.
 *
 * <p>기존 {@code infra/monitoring.tf}의 AWS Chatbot 연동은 SNS 토픽을 구독하는 CloudWatch
 * 알람 전용이라, 정해진 시각에 임의 메시지를 보내는 용도로는 재사용할 수 없다.
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
        this.webhookUrl = webhookUrl;
        if (!isEnabled()) {
            log.warn("metrics.slack.webhook-url이 비어 있어 일일 지표 리포트를 발송하지 않는다");
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
        }
    }
}
