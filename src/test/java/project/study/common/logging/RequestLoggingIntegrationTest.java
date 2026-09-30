package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/**
 * 필터·BodyAdvice·컨트롤러가 실제로 엮였을 때 액세스 로그 이벤트의 MDC에 userId·requestId가
 * 실리는지 검증한다. 단위테스트로는 "필터가 응답 뒤에 로그를 찍는 시점에 BodyAdvice가 넣은 값이
 * 살아 있는가"를 증명할 수 없어서 컨텍스트를 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RequestLoggingIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        accessLogger.detachAppender(appender);
    }

    private ILoggingEvent accessLog() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }

    @Test
    void POST_body의_userId가_액세스_로그_MDC에_실린다() {
        mvc.post()
                .uri("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 99}")
                .exchange();

        assertThat(accessLog().getMDCPropertyMap())
                .containsEntry(LogContext.USER_ID, "99")
                .containsKey(LogContext.REQUEST_ID);
    }

    @Test
    void GET_쿼리의_userId가_액세스_로그_MDC에_실린다() {
        mvc.get().uri("/api/stats/streak").param("userId", "5").exchange();

        assertThat(accessLog().getMDCPropertyMap()).containsEntry(LogContext.USER_ID, "5");
    }

    @Test
    void 경로_변수의_userId가_액세스_로그_MDC에_실린다() {
        mvc.get().uri("/api/users/13/profile").exchange();

        assertThat(accessLog().getMDCPropertyMap()).containsEntry(LogContext.USER_ID, "13");
    }

    @Test
    void 응답에_X_Request_Id가_돌아온다() {
        MvcTestResult result =
                mvc.get().uri("/api/stats/streak").param("userId", "5").exchange();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    void actuator_health는_액세스_로그를_남기지_않는다() {
        mvc.get().uri("/actuator/health").exchange();

        assertThat(appender.list).isEmpty();
    }
}
