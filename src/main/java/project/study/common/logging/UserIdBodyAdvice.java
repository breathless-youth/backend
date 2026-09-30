package project.study.common.logging;

import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * {@code @RequestBody} 역직렬화 직후 {@link UserScopedRequest}의 userId를 MDC에 싣는다.
 *
 * <p>정리는 하지 않는다 — 액세스 로그를 찍는 {@link RequestLoggingFilter}가 응답 뒤에 MDC를
 * 통째로 비운다. 여기서 지우면 필터가 로그를 남기는 시점에 값이 사라져 액세스 로그에 유저가 빠진다.
 */
@RestControllerAdvice
public class UserIdBodyAdvice extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(
            MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        if (body instanceof UserScopedRequest scoped) {
            LogContext.putUserId(scoped.userId());
        }
        return body;
    }
}
