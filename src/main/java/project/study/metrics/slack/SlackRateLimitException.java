package project.study.metrics.slack;

/**
 * Slack 요청 한도(HTTP 429)가 Retry-After만큼 여러 번 기다려도 풀리지 않았다. 발송기 안에서 이미 기다렸으므로 @Retryable의 짧은 간격
 * 재시도에서 뺀다 — 한도 초과에 짧은 재시도를 얹으면 한도를 더 오래 끈다.
 */
public class SlackRateLimitException extends SlackNotificationException {

    public SlackRateLimitException(String message) {
        super(message);
    }
}
