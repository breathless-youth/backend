package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * 이 기능의 전제 — "prod의 ECS JSON 포맷은 MDC를 최상위 필드로 싣는다" — 를 실제 콘솔 출력으로 못박는다.
 * 이 전제가 깨지면 CloudWatch Insights의 {@code filter userId = ...} 조회가 조용히 빈 결과를 낸다.
 */
@SpringBootTest(properties = "logging.structured.format.console=ecs")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EcsStructuredLogFormatTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 액세스_로그_JSON에_userId와_requestId가_최상위_필드로_실린다(CapturedOutput output) {
        mvc.get()
                .uri("/api/stats/streak")
                .param("userId", "77")
                .header("X-Request-Id", "req-ecs-check")
                .exchange();

        String accessLine = output.getOut()
                .lines()
                .filter(line -> line.contains("\"requestId\":\"req-ecs-check\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("액세스 로그 JSON 줄을 찾지 못했다:\n" + output.getOut()));

        assertThat(accessLine).startsWith("{").contains("\"userId\":\"77\"").contains("\"ecs\":{\"version\"");
    }
}
