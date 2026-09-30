package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * 구 앱(v1.2.x) 유저 계약 — 헤더·토큰 없이 등록하고 경로의 userId로 프로필을 다룬다 (ADR-0020).
 * 비즈니스 규칙은 서비스가 v2와 같으므로 여기서는 계약 모양(userId 채널·응답 필드)만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyUserApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private MvcTestResult register(String deviceId) {
        return mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}")
                .exchange();
    }

    private long registerUser() {
        MvcTestResult result = register(UUID.randomUUID().toString());
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    @Test
    void 헤더_없는_등록은_userId와_isNew만_돌려주고_토큰은_없다() {
        assertThat(register(UUID.randomUUID().toString()))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.userId", v -> assertThat(v).isNotNull())
                .hasPathSatisfying("$.isNew", v -> assertThat(v).isEqualTo(true))
                .doesNotHavePath("$.accessToken")
                .doesNotHavePath("$.refreshToken");
    }

    @Test
    void 헤더_없는_재등록은_200이고_토큰이_없다() {
        String deviceId = UUID.randomUUID().toString();
        assertThat(register(deviceId)).hasStatus(HttpStatus.CREATED);

        assertThat(register(deviceId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.isNew", v -> assertThat(v).isEqualTo(false))
                .doesNotHavePath("$.accessToken");
    }

    @Test
    void 헤더_토큰_없이_경로_userId로_프로필을_조회한다() {
        long userId = registerUser();

        assertThat(mvc.get().uri("/api/users/" + userId + "/profile"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().matches("포메\\d{5}"));
    }

    @Test
    void 헤더_토큰_없이_경로_userId로_프로필을_수정한다() {
        long userId = registerUser();

        assertThat(mvc.patch()
                        .uri("/api/users/" + userId + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\": \"올해 안에 이직 성공\"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goal", v -> assertThat(v).isEqualTo("올해 안에 이직 성공"));
    }

    @Test
    void 없는_userId로_프로필을_조회하면_404다() {
        assertThat(mvc.get().uri("/api/users/999999999/profile")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void API_Version_2_요청은_경로_userId_프로필에_닿지_않는다() {
        long userId = registerUser();

        // 고정 버전 1 핸들러는 요청 버전 2와 불일치 — 새 앱이 옛 URL을 부르면 400으로 드러난다
        assertThat(mvc.get().uri("/api/users/" + userId + "/profile").with(asUser(userId)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
