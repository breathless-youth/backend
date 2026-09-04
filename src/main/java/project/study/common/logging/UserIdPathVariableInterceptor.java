package project.study.common.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 경로 변수 {@code {userId}}({@code /api/users/{userId}/profile} 등)를 MDC에 싣는다.
 *
 * <p>경로 변수는 핸들러 매핑이 풀어야 알 수 있어 서블릿 필터 시점에는 없다. 매핑 직후 실행되는
 * 인터셉터에서 {@link HandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE}를 읽는다. 정리는
 * {@link RequestLoggingFilter}가 응답 뒤에 한다.
 */
public class UserIdPathVariableInterceptor implements HandlerInterceptor {

    private static final String USER_ID_VARIABLE = "userId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map) {
            LogContext.putUserId(map.get(USER_ID_VARIABLE));
        }
        return true;
    }
}
