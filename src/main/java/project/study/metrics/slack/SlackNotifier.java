package project.study.metrics.slack;

/**
 * Slack 메시지 발송. 인터페이스로 두어 단위 테스트에서 실제 HTTP 없이 대체할 수 있게 한다.
 */
public interface SlackNotifier {

    /** 발송 수단이 설정돼 있는지. 비활성이면 호출자는 발송 이력을 남기지 않고 건너뛴다. */
    boolean isEnabled();

    void send(String message);
}
