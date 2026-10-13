package project.study.metrics.slack;

import org.springframework.resilience.annotation.Retryable;

/**
 * Slack 발송 — 본 메시지를 올리고 그 ts에 스레드 댓글을 단다(스펙 §9). 인터페이스로 두어 단위 테스트에서 실제 HTTP 없이 대체한다.
 *
 * <p>@Retryable은 컨테이너가 만든 프록시를 거칠 때만 동작하고, 구현체는 인터페이스 기반 JDK 동적 프록시로 감싸지므로 이 인터페이스
 * 메서드에 붙인다. 최초 포함 3회, 하루 한 번 도는 작업이라 짧은 고정 간격이다. 요청 한도(429)는 발송기가 Retry-After대로 이미
 * 기다렸으므로 재시도에서 뺀다.
 */
public interface SlackNotifier {

    /** 봇 토큰·채널이 설정돼 있는지. 꺼져 있으면 호출자는 리포트 계산부터 건너뛴다. */
    boolean isEnabled();

    /** 본 메시지를 올리고 스레드를 달 ts를 돌려준다. */
    @Retryable(
            includes = SlackNotificationException.class,
            excludes = SlackRateLimitException.class,
            maxRetries = 2,
            delay = 200)
    String post(String text);

    @Retryable(
            includes = SlackNotificationException.class,
            excludes = SlackRateLimitException.class,
            maxRetries = 2,
            delay = 200)
    void reply(String threadTs, String text);
}
