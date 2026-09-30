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

/** BY-490 WebRTC 연결 통계 수집 API — 저장·검증(candidateType 허용값·필수값·바운드)을 다룬다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RtcStatApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM rtc_connection_stat");
    }

    private MvcTestResult post(String body) {
        return mvc.post()
                .uri("/api/rtc-stats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void 유효한_relay_통계는_저장되고_204다() {
        String body = """
                {"connectionId":"c-1","roomId":1,"userId":2,"peerUserId":3,"candidateType":"relay","relayProtocol":"udp","bytesReceived":1000,"bytesSent":500,"rttMs":40,"isFinal":true,"at":1700000000000}""";

        assertThat(post(body)).hasStatus(HttpStatus.NO_CONTENT);

        Integer relayRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rtc_connection_stat WHERE candidate_type = 'relay' AND connection_id = 'c-1'",
                Integer.class);
        assertThat(relayRows).isEqualTo(1);
    }

    @Test
    void 최소_필드만으로도_저장된다() {
        String body = """
                {"connectionId":"c-2","roomId":1,"userId":2,"candidateType":"host","isFinal":false}""";

        assertThat(post(body)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void candidateType이_허용값이_아니면_400이다() {
        String body = """
                {"connectionId":"c-3","roomId":1,"userId":2,"candidateType":"wat","isFinal":false}""";

        assertThat(post(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void connectionId_누락은_400이다() {
        String body = """
                {"roomId":1,"userId":2,"candidateType":"host","isFinal":false}""";

        assertThat(post(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 필수값_누락은_400이다() {
        // roomId, isFinal 누락
        String body = """
                {"connectionId":"c-4","userId":2,"candidateType":"host"}""";

        assertThat(post(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 음수_bytes는_400이다() {
        String body = """
                {"connectionId":"c-5","roomId":1,"userId":2,"candidateType":"relay","bytesReceived":-1,"isFinal":true}""";

        assertThat(post(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
