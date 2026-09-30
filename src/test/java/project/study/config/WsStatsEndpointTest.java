package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/** BY-491 WS 통계 관측 엔드포인트 — 부하테스트가 send-limit 카운트를 수집하는 경로를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.endpoints.web.exposure.include=wsstats")
class WsStatsEndpointTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void wsstats가_세션_통계와_send_limit_카운트를_반환한다() {
        assertThat(mvc.get().uri("/actuator/wsstats").exchange())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                // 송신버퍼 한도 초과 종료 수 — 부하테스트 스냅샷이 수집하는 핵심 필드
                .hasPathSatisfying(
                        "$.sendLimitExceededSessions", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.currentWsSessions", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.stompConnect", v -> assertThat(v).isEqualTo(0));
    }
}
