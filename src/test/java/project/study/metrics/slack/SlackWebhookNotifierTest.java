package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SlackWebhookNotifierTest {

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
    void 비활성_상태에서_발송해도_예외를_던지지_않는다() {
        // 설정 누락이 예외로 번지면 스케줄러가 매일 실패 알림을 만든다 — 조용히 넘긴다
        assertThatCode(() -> notifier("").send("무시되는 메시지")).doesNotThrowAnyException();
    }
}
