package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.support.AuthTestSupport;
import project.study.user.LinkTestClient.Device;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/**
 * 탈퇴·병합 뒤 최대 30분 남는 옛 access 토큰(ADR-0043)으로 오는 쓰기 — 지워진 사용자를 가리키는 FK 위반은 500이 아니라 404
 * {@code USER_NOT_FOUND}이고, FK가 없는 RTC 통계는 지워진 사람의 행을 다시 만들지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaleTokenWriteApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private SocialTokenVerifiers verifiers;

    private Device deleted;

    @BeforeEach
    void deleteAccount() {
        deleted = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers).register();
        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 옛_토큰으로_푸시_토큰을_등록하면_404다() {
        assertUserNotFound(write(
                mvc.put().uri("/api/push-tokens"),
                ApiVersionConfig.DEFAULT_VERSION,
                "{\"token\":\"fcm-" + UUID.randomUUID() + "\",\"platform\":\"IOS\",\"notificationsEnabled\":true}"));
        assertThat(rows("push_token")).isZero();
    }

    @Test
    void 옛_토큰으로_과목을_만들면_404다() {
        assertUserNotFound(
                write(mvc.post().uri("/api/subjects"), ApiVersionConfig.DEFAULT_VERSION, "{\"name\":\"수학\"}"));
        assertThat(rows("study_subject")).isZero();
    }

    @Test
    void 옛_토큰의_RTC_통계는_지워진_사람의_행을_다시_만들지_않는다() {
        String body = """
                {"connectionId":"stale-%s","roomId":1,"candidateType":"host","isFinal":true}""".formatted(UUID.randomUUID());

        assertUserNotFound(write(mvc.post().uri("/api/rtc-stats"), AuthTestSupport.TOKEN_API_VERSION, body));
        assertThat(rows("rtc_connection_stat")).isZero();
    }

    private MvcTestResult write(MockMvcTester.MockMvcRequestBuilder request, String apiVersion, String body) {
        return request.header(ApiVersionConfig.HEADER, apiVersion)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String bearer() {
        return "Bearer " + deleted.accessToken();
    }

    private long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", deleted.userId())
                .query(Long.class)
                .single();
    }

    private static void assertUserNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("USER_NOT_FOUND");
    }
}
