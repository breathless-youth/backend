package project.study.metrics.slack;

import org.springframework.resilience.annotation.Retryable;

/**
 * Slack 메시지 발송. 인터페이스로 두어 단위 테스트에서 실제 HTTP 없이 대체할 수 있게 한다.
 */
public interface SlackNotifier {

    /** 발송 수단이 설정돼 있는지. 비활성이면 호출자는 발송 이력을 남기지 않고 건너뛴다. */
    boolean isEnabled();

    // @Retryable은 Spring 컨테이너가 만든 프록시를 통해 호출될 때만 동작한다. 구현체는 기본적으로
    // 인터페이스 기반 JDK 동적 프록시로 감싸지므로, 프록시가 실제로 디스패치하는 이 인터페이스
    // 메서드에 애노테이션을 붙여야 한다 — 구현 클래스의 메서드에 붙이면 무시된다.
    // 최초 시도 포함 총 3회(최초 1회 + 재시도 2회)만 시도하고 포기한다. 하루 한 번 도는 스케줄러라
    // 지연에 민감하지 않으므로 백오프는 짧게 고정 간격으로 둔다.
    @Retryable(includes = SlackNotificationException.class, maxRetries = 2, delay = 200)
    void send(String message);
}
