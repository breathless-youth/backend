package project.study.metrics.slack;

/**
 * Slack 발송 실패를 감싸는 예외.
 *
 * <p>원인 예외를 그대로 던지거나 cause로 붙이지 않는다. 그 메시지에 요청 헤더·URL이 들어갈 수 있고(예: 잘못된 헤더 값 예외는
 * "Bearer xoxb-…"를 통째로 담는다) 로그(CloudWatch)와 Sentry로 봇 토큰이 새기 때문이다. 봇 토큰은 그 자체가 자격증명이라 소지자는
 * 누구나 그 봇으로 채널에 글을 쓸 수 있다 — 그래서 진단 정보(예외 타입·HTTP 상태코드·Slack 오류 코드)만 메시지에 담는다.
 */
public class SlackNotificationException extends RuntimeException {

    public SlackNotificationException(String message) {
        super(message);
    }
}
