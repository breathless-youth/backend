package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/** 구 앱(v1.2.x)의 핸드셰이크 {@code ?userId=}를 세션 속성으로 옮기는 인터셉터 (ADR-0020). */
class LegacyUserIdHandshakeInterceptorTest {

    private final WebSocketConfig.LegacyUserIdHandshakeInterceptor interceptor =
            new WebSocketConfig.LegacyUserIdHandshakeInterceptor();

    private Map<String, Object> handshake(String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        if (query != null) servletRequest.setParameter("userId", query);
        Map<String, Object> attributes = new HashMap<>();
        boolean proceed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);
        assertThat(proceed).isTrue();
        return attributes;
    }

    @Test
    void 쿼리_userId를_세션_속성에_그대로_옮긴다() {
        assertThat(handshake("9")).containsEntry("userId", "9");
    }

    @Test
    void 쿼리_userId가_없으면_속성을_만들지_않고_핸드셰이크는_계속된다() {
        // 새 앱은 쿼리 없이 접속한다 — 여기서 막지 않고 CONNECT 인터셉터가 Authorization으로 판정한다
        assertThat(handshake(null)).doesNotContainKey("userId");
    }
}
