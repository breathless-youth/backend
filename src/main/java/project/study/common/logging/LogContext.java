package project.study.common.logging;

import org.slf4j.MDC;

/**
 * 로그 컨텍스트(MDC) 키 모음.
 *
 * <p>prod의 ECS JSON 포맷은 MDC의 모든 항목을 최상위 필드로 싣는다. 그래서 여기 키 이름이 곧
 * CloudWatch Logs Insights의 필드 이름이다 — {@code filter userId = 42}처럼 바로 조회된다.
 */
public final class LogContext {

    /** 요청·메시지를 보낸 유저의 ID. HTTP는 JwtFilter가 SecurityContext에서, STOMP는 프린시펄에서 채운다. */
    public static final String USER_ID = "userId";

    /** 요청 하나를 식별하는 ID. */
    public static final String REQUEST_ID = "requestId";

    /**
     * 요청의 유효 API 버전 — 헤더가 없으면 기본버전 {@code 1}. 구 앱(v1.2.x) 병행 중 구 호출량을 세는 근거다:
     * {@code filter apiVersion = "1" and path like /api/} (ADR-0020, ADR-0015 결정 5).
     */
    public static final String API_VERSION = "apiVersion";

    private LogContext() {}

    /** null이면 키를 만들지 않는다 — 빈 값이 필드로 실려 필터 조회를 오염시키지 않게. */
    public static void putUserId(Object userId) {
        if (userId != null) {
            MDC.put(USER_ID, String.valueOf(userId));
        }
    }
}
