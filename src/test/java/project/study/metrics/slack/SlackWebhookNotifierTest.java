package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SlackWebhookNotifierTest {

    // 자격증명처럼 취급해야 하는 조각 — 실패 시 예외 메시지·원인 체인 어디에도 나타나면 안 된다
    private static final String SECRET_TOKEN = "T00000000/B00000000/verySecretSlackToken1234567890";
    // 아무도 리스닝하지 않는 로컬 포트 — 외부 네트워크·DNS 없이 즉시 connection refused를 재현한다
    private static final String UNREACHABLE_WEBHOOK_URL = "http://127.0.0.1:1/services/" + SECRET_TOKEN;

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

    @Test
    void 발송_실패시_SlackNotificationException으로_감싼다() {
        assertThatThrownBy(() -> notifier(UNREACHABLE_WEBHOOK_URL).send("메시지"))
                .isInstanceOf(SlackNotificationException.class);
    }

    @Test
    void 발송_실패_예외에_webhook_URL이_어디에도_남지_않는다() {
        // Webhook URL은 그 자체가 자격증명이다 — I/O 실패 시 던지는 예외(원본은
        // ResourceAccessException)의 메시지에 전체 URL이 그대로 들어가면 로그(CloudWatch)와
        // Sentry로 유출된다. 메시지뿐 아니라 원인 체인 전체를 훑어 URL 조각이 없는지 확인한다.
        Throwable thrown =
                catchThrowable(() -> notifier(UNREACHABLE_WEBHOOK_URL).send("메시지"));

        assertThat(thrown).isNotNull();
        List<String> allDiagnosticText = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            allDiagnosticText.add(String.valueOf(t.getMessage()));
            allDiagnosticText.add(t.toString());
        }

        assertThat(allDiagnosticText)
                .noneMatch(text -> text.contains(SECRET_TOKEN))
                .noneMatch(text -> text.contains(UNREACHABLE_WEBHOOK_URL))
                .noneMatch(text -> text.contains("127.0.0.1"));
        // 원인을 아예 붙이지 않는 것이 URL 미유출을 보장하는 가장 확실한 방법이다
        assertThat(thrown.getCause()).isNull();
    }
}
