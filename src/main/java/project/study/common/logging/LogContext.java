package project.study.common.logging;

import org.slf4j.MDC;

/**
 * 로그 컨텍스트(MDC) 키 모음.
 *
 * <p>prod의 ECS JSON 포맷은 MDC의 모든 항목을 최상위 필드로 싣는다. 그래서 여기 키 이름이 곧
 * CloudWatch Logs Insights의 필드 이름이다 — {@code filter userId = 42}처럼 바로 조회된다.
 */
public final class LogContext {

    /** 요청·메시지를 보낸 유저의 ID. HTTP는 쿼리/바디에서, STOMP는 프린시펄에서 채운다. */
    public static final String USER_ID = "userId";

    /** 요청 하나를 식별하는 ID. 클라이언트가 X-Request-Id로 보내면 그대로, 없으면 서버가 만든다. */
    public static final String REQUEST_ID = "requestId";

    private LogContext() {}

    /** {@code null}이면 키를 만들지 않는다 — 빈 값이 필드로 실려 필터 조회를 오염시키지 않게. */
    static void putUserId(Object userId) {
        if (userId != null) {
            MDC.put(USER_ID, String.valueOf(userId));
        }
    }
}
