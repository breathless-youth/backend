package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

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
import project.study.user.dto.TokenResponse;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.jwt.JwtUtil;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT·refresh 왕복을 검증하는 유일한 API 테스트. 다른 API 테스트는 AuthTestSupport로 principal만 주입한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void 기기_등록은_userId와_함께_access_refresh_쌍을_발급한다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        assertThat(jwtUtil.getUserId(device.accessToken())).isEqualTo(String.valueOf(device.userId()));
        assertThat(device.refreshToken()).hasSize(36); // opaque UUID
    }

    @Test
    void refresh는_새_토큰쌍을_발급하고_구_토큰_재사용_시_전체를_폐기한다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        MvcTestResult result = refreshRequest(device.refreshToken()).exchange();
        assertThat(result).hasStatusOk();
        TokenResponse rotated = readBody(result, TokenResponse.class);
        assertThat(rotated.refreshToken()).isNotEqualTo(device.refreshToken());
        assertThat(jwtUtil.getUserId(rotated.accessToken())).isEqualTo(String.valueOf(device.userId()));

        // 회전된 구 토큰 재사용 → 거부 + 탈취 의심으로 전체 폐기 (방금 발급한 새 토큰까지)
        assertThat(refreshRequest(device.refreshToken()))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(refreshRequest(rotated.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 같은_기기를_다시_등록하면_이전_refresh_토큰은_무효가_된다() {
        String deviceId = UUID.randomUUID().toString();
        UserRegisterResponse first = registerDevice(deviceId);
        UserRegisterResponse again = registerDevice(deviceId);

        assertThat(again.userId()).isEqualTo(first.userId());
        assertThat(again.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(refreshRequest(first.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refreshRequest(again.refreshToken())).hasStatusOk();
    }

    @Test
    void 알_수_없는_refresh_토큰은_401_INVALID_REFRESH_TOKEN이다() {
        assertThat(refreshRequest(UUID.randomUUID().toString()))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void access_토큰으로는_refresh할_수_없다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        // JWT는 refresh 상한(64자)을 넘어 DTO 검증에서 400으로 걸러진다 — 어느 쪽이든 재발급은 불가
        assertThat(refreshRequest(device.accessToken())).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private UserRegisterResponse registerDevice(String deviceId) {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}")
                .exchange();
        assertThat(result).hasStatus2xxSuccessful();
        return readBody(result, UserRegisterResponse.class);
    }

    private MockMvcTester.MockMvcRequestBuilder refreshRequest(String refreshToken) {
        return mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private <T> T readBody(MvcTestResult result, Class<T> type) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }
}
