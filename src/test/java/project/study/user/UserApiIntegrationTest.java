package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.service.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserApiIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvcTester.MockMvcRequestBuilder registerRequest(String deviceId) {
        return mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}");
    }

    @Test
    void 새_기기는_유저를_생성하고_201과_isNew_true를_반환한다() {
        assertThat(registerRequest(UUID.randomUUID().toString()))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.userId", id -> assertThat(id).isNotNull())
                .extractingPath("$.isNew")
                .isEqualTo(true);
    }

    @Test
    void 같은_기기의_재등록은_200과_같은_userId를_반환한다() {
        String deviceId = UUID.randomUUID().toString();
        MvcTestResult first = registerRequest(deviceId).exchange();
        assertThat(first).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = registerRequest(deviceId).exchange();
        assertThat(second).hasStatusOk();
        assertThat(readBody(second).userId()).isEqualTo(readBody(first).userId());
        assertThat(readBody(second).isNew()).isFalse();
    }

    @Test
    void 대소문자만_다른_deviceId는_같은_유저다() {
        String deviceId = UUID.randomUUID().toString();
        MvcTestResult first = registerRequest(deviceId.toLowerCase(Locale.ROOT)).exchange();
        assertThat(first).hasStatus(HttpStatus.CREATED);

        MvcTestResult second =
                registerRequest(deviceId.toUpperCase(Locale.ROOT)).exchange();
        assertThat(second).hasStatusOk();
        assertThat(readBody(second).userId()).isEqualTo(readBody(first).userId());
    }

    @Test
    void 같은_기기의_동시_등록_경쟁에서도_유저는_하나만_생성된다() throws Exception {
        String deviceId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 두 요청이 실제 DB의 유니크 제약 충돌 경로를 타도록 동시에 출발시킨다
            CountDownLatch start = new CountDownLatch(1);
            Callable<UserRegisterResponse> task = () -> {
                start.await();
                return userService.register(new UserRegisterRequest(deviceId));
            };
            Future<UserRegisterResponse> first = executor.submit(task);
            Future<UserRegisterResponse> second = executor.submit(task);
            start.countDown();

            UserRegisterResponse r1 = first.get();
            UserRegisterResponse r2 = second.get();

            assertThat(r1.userId()).isEqualTo(r2.userId());
            assertThat(r1.isNew() ^ r2.isNew()).as("정확히 한쪽만 신규여야 한다").isTrue();
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE provider = 'DEVICE' AND provider_user_id = ?",
                    Integer.class,
                    deviceId);
            assertThat(count).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void UUID_형식이_아니면_400이다() {
        assertThat(registerRequest("not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deviceId가_없으면_400이다() {
        assertThat(mvc.post()
                        .uri("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // getContentAsString과 달리 getContentAsByteArray는 checked 예외가 없어 테스트에 throws가 안 번진다
    private UserRegisterResponse readBody(MvcTestResult result) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), UserRegisterResponse.class);
    }
}
