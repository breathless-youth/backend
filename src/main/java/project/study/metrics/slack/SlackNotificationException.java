package project.study.metrics.slack;

/**
 * Slack 발송 실패를 감싸는 예외.
 *
 * <p>원인 예외(예: {@code ResourceAccessException})를 그대로 던지거나 cause로 붙이면 그 메시지에
 * webhook URL이 통째로 들어 있어("I/O error on POST request for \"https://hooks.slack.com/...\": ...")
 * 로그(CloudWatch)와 Sentry로 URL이 유출된다. Slack Incoming Webhook URL은 그 자체가 자격증명이라
 * 소지자는 누구나 해당 채널에 임의 메시지를 게시할 수 있다 — 그래서 원인은 붙이지 않고, URL이 아닌
 * 진단 정보(예외 타입·HTTP 상태코드)만 메시지에 담는다.
 */
public class SlackNotificationException extends RuntimeException {

    public SlackNotificationException(String message) {
        super(message);
    }
}
