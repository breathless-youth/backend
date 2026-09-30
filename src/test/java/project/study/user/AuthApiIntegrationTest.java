package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.TOKEN_API_VERSION;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.user.dto.TokenResponse;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.repository.UserRepository;
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

    @Autowired
    private UserRepository userRepository;

    @Test
    void 기기_등록은_access_refresh_쌍을_발급하고_userId는_access_토큰_sub로만_전달한다() {
        String deviceId = UUID.randomUUID().toString();
        UserRegisterResponse device = registerDevice(deviceId);

        // 응답 본문에 userId 필드는 없다(UserApiIntegrationTest) — FE는 access 토큰의 sub에서 읽는다.
        // sub가 "숫자"인 것만으로는 부족하고, 이 deviceId로 실제 만들어진 유저의 id여야 한다
        Long registeredId = userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow()
                .getId();
        assertThat(jwtUtil.getUserId(device.accessToken())).isEqualTo(String.valueOf(registeredId));
        assertThat(device.refreshToken()).hasSize(36); // opaque UUID
    }

    @Test
    void access_토큰으로_보호된_API에_접근할_수_있다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        assertThat(mvc.get()
                        .uri("/api/users/me/profile")
                        .header(ApiVersionConfig.HEADER, TOKEN_API_VERSION)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().matches("포메\\d{5}"));
    }

    @Test
    void 토큰_없이_보호된_API에_접근하면_401_UNAUTHORIZED_JSON이다() {
        assertThat(mvc.get().uri("/api/users/me/profile"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("UNAUTHORIZED"))
                .hasPathSatisfying("$.message", v -> assertThat(v).asString().isNotBlank());
    }

    @Test
    void 위조된_access_토큰이나_refresh_토큰으로는_보호된_API에_접근할_수_없다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        // refresh(opaque UUID)는 JWT 파싱 자체가 실패한다 — API 접근 수단이 아니다
        assertThat(mvc.get()
                        .uri("/api/users/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.refreshToken()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/users/me/profile").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/users/me/profile").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 토큰_계약에서_인증_없이_열린_경로는_등록_refresh_헬스체크뿐이다() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        // GET /api/users 같은 다른 메서드·경로는 열려 있지 않다
        assertThat(mvc.get().uri("/api/users")).hasStatus(HttpStatus.UNAUTHORIZED);
        // 구 앱 경로도 API-Version: 2면 토큰이 필요하다 — 헤더 없는 경우는 LegacyApiSecurityTest (ADR-0020)
        assertThat(mvc.get().uri("/api/stats/streak").header(ApiVersionConfig.HEADER, TOKEN_API_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh는_새_토큰쌍을_발급하고_구_토큰_재사용_시_전체를_폐기한다() {
        UserRegisterResponse device = registerDevice(UUID.randomUUID().toString());

        MvcTestResult result = refreshRequest(device.refreshToken()).exchange();
        assertThat(result).hasStatusOk();
        TokenResponse rotated = readBody(result, TokenResponse.class);
        assertThat(rotated.refreshToken()).isNotEqualTo(device.refreshToken());
        assertThat(jwtUtil.getUserId(rotated.accessToken())).isEqualTo(jwtUtil.getUserId(device.accessToken()));

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

        assertThat(jwtUtil.getUserId(again.accessToken())).isEqualTo(jwtUtil.getUserId(first.accessToken()));
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

    // 토큰 쌍은 토큰 계약(API-Version: 2)의 등록에서만 발급된다 — 헤더 없는 등록은 구 앱 계약 (ADR-0020)
    private UserRegisterResponse registerDevice(String deviceId) {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .header(ApiVersionConfig.HEADER, TOKEN_API_VERSION)
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
