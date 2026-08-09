package project.study.metrics.slack;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Slack Incoming Webhook 발송기.
 *
 * <p>기존 {@code infra/monitoring.tf}의 AWS Chatbot 연동은 SNS 토픽을 구독하는 CloudWatch
 * 알람 전용이라, 정해진 시각에 임의 메시지를 보내는 용도로는 재사용할 수 없다.
 */
@Slf4j
@Component
public class SlackWebhookNotifier implements SlackNotifier {

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackWebhookNotifier(
            RestClient.Builder restClientBuilder, @Value("${metrics.slack.webhook-url:}") String webhookUrl) {
        this.restClient = restClientBuilder.build();
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
        restClient
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", message))
                .retrieve()
                .toBodilessEntity();
    }
}
