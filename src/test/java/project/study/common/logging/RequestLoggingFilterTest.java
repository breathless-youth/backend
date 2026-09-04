package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** DB·Spring 컨텍스트 없이 필터 하나만 띄워 MDC 적재·정리와 액세스 로그 한 줄을 검증한다. */
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    /** 체인 안에서 보이는 MDC를 붙잡아 두는 체인 — 필터가 값을 "언제" 넣는지 검증하는 용도. */
    private static final class CapturingChain implements FilterChain {
        final Map<String, String> mdcInsideChain = new HashMap<>();
        int status = 200;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            Map<String, String> copy = MDC.getCopyOfContextMap();
            if (copy != null) mdcInsideChain.putAll(copy);
            ((MockHttpServletResponse) response).setStatus(status);
        }
    }

    @Test
    void 요청_ID_헤더가_없으면_생성해서_MDC와_응답_헤더에_싣는다() throws ServletException, IOException {
        CapturingChain chain = new CapturingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/rooms"), response, chain);

        String requestId = chain.mdcInsideChain.get(LogContext.REQUEST_ID);
        assertThat(requestId).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId);
    }

    @Test
    void 요청_ID_헤더가_오면_그_값을_그대로_쓴다() throws ServletException, IOException {
        CapturingChain chain = new CapturingChain();
        MockHttpServletRequest request = get("/api/rooms");
        request.addHeader("X-Request-Id", "client-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.mdcInsideChain).containsEntry(LogContext.REQUEST_ID, "client-abc");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-abc");
    }

    @Test
    void 쿼리_파라미터_userId가_있으면_MDC에_싣는다() throws ServletException, IOException {
        CapturingChain chain = new CapturingChain();
        MockHttpServletRequest request = get("/api/stats/streak");
        request.setParameter("userId", "42");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.mdcInsideChain).containsEntry(LogContext.USER_ID, "42");
    }

    @Test
    void 쿼리_파라미터_userId가_없으면_MDC에_userId_키를_만들지_않는다() throws ServletException, IOException {
        CapturingChain chain = new CapturingChain();

        filter.doFilter(get("/api/rooms"), new MockHttpServletResponse(), chain);

        assertThat(chain.mdcInsideChain).doesNotContainKey(LogContext.USER_ID);
    }

    @Test
    void 응답_후_액세스_로그_한_줄을_INFO로_남긴다() throws ServletException, IOException {
        CapturingChain chain = new CapturingChain();
        chain.status = 201;
        MockHttpServletRequest request = get("/api/rooms");
        request.setMethod("POST");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("POST", "/api/rooms", "201");
        // 로그 시점에 MDC가 살아 있어야 ECS JSON에 requestId 필드가 실린다
        assertThat(event.getMDCPropertyMap()).containsKey(LogContext.REQUEST_ID);
    }

    @Test
    void 처리가_끝나면_MDC를_비운다() throws ServletException, IOException {
        MockHttpServletRequest request = get("/api/rooms");
        request.setParameter("userId", "42");

        filter.doFilter(request, new MockHttpServletResponse(), new CapturingChain());

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void 체인에서_예외가_나도_MDC를_비운다() {
        FilterChain throwingChain = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(get("/api/rooms"), new MockHttpServletResponse(), throwingChain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void actuator_경로는_로그를_남기지_않는다() throws ServletException, IOException {
        filter.doFilter(get("/actuator/health"), new MockHttpServletResponse(), new CapturingChain());

        assertThat(appender.list).isEmpty();
    }
}
