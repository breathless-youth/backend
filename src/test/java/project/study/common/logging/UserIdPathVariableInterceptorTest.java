package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** 핸들러 매핑이 풀어둔 경로 변수 중 userId만 MDC로 옮기는지 본다. */
class UserIdPathVariableInterceptorTest {

    private final UserIdPathVariableInterceptor interceptor = new UserIdPathVariableInterceptor();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 경로_변수_userId를_MDC에_싣는다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/13/profile");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("userId", "13"));

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo("13");
    }

    @Test
    void 경로_변수가_없으면_MDC를_건드리지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void userId가_아닌_경로_변수는_무시한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rooms/7/leave");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("roomId", "7"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }
}
