package project.study.rtcstats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** 구 앱(v1.2.x) WebRTC 통계 계약 — 헤더·토큰 없이 본문의 userId로 식별한다 (ADR-0020). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyRtcStatApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM rtc_connection_stat WHERE connection_id LIKE 'legacy-%'");
    }

    private MvcTestResult post(String body) {
        return mvc.post()
                .uri("/api/rtc-stats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void 본문_userId로_통계를_보내면_그_유저의_행으로_저장되고_204다() {
        String body = """
                {"connectionId":"legacy-1","roomId":1,"userId":42,"peerUserId":3,"candidateType":"relay","relayProtocol":"udp","bytesReceived":1000,"bytesSent":500,"rttMs":40,"isFinal":true,"at":1700000000000}""";

        assertThat(post(body)).hasStatus(HttpStatus.NO_CONTENT);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rtc_connection_stat WHERE connection_id = 'legacy-1' AND user_id = 42",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void userId_없이_보내면_400이다() {
        String body = """
                {"connectionId":"legacy-2","roomId":1,"candidateType":"host","isFinal":false}""";

        assertThat(post(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
