package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** body 역직렬화 직후 훅만 직접 호출한다 — 어드바이스가 MDC에 userId를 싣는지가 전부다. */
class UserIdBodyAdviceTest {

    private final UserIdBodyAdvice advice = new UserIdBodyAdvice();

    private record ScopedBody(Long userId, String other) implements UserScopedRequest {}

    private record PlainBody(String name) {}

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 모든_body에_대해_동작한다() {
        assertThat(advice.supports(null, null, null)).isTrue();
    }

    @Test
    void userId를_가진_요청_body면_MDC에_싣는다() {
        Object returned = advice.afterBodyRead(new ScopedBody(7L, "x"), null, null, null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo("7");
        assertThat(returned).isInstanceOf(ScopedBody.class);
    }

    @Test
    void userId가_null이면_MDC에_넣지_않는다() {
        advice.afterBodyRead(new ScopedBody(null, "x"), null, null, null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void 마커가_없는_body는_무시한다() {
        Object body = new PlainBody("n");

        Object returned = advice.afterBodyRead(body, null, null, null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
        assertThat(returned).isSameAs(body);
    }
}
