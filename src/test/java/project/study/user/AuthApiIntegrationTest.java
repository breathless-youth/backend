package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.TokenResponse;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.oauth.GoogleTokenVerifier;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthUserInfo;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        when(googleTokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    }

    @Test
    void 첫_link는_전환으로_isNewUser가_true_같은_계정_재link는_병합으로_false다() {
        assertThat(login("sub-new").isNewUser()).isTrue();
        assertThat(login("sub-new").isNewUser()).isFalse();
    }

    @Test
    void 액세스_토큰으로_보호된_API에_접근할_수_있다() {
        LoginResponse tokens = login("sub-access");

        assertThat(logoutRequest(tokens.accessToken(), tokens.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 로그아웃하면_해당_refresh_토큰으로_재발급할_수_없다() {
        LoginResponse tokens = login("sub-logout");

        assertThat(logoutRequest(tokens.accessToken(), tokens.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(refreshRequest(tokens.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 토큰_없이_보호된_API에_접근하면_401이다() {
        assertThat(mvc.post().uri("/api/auth/logout")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_토큰으로는_보호된_API에_접근할_수_없다() {
        LoginResponse tokens = login("sub-category");

        assertThat(mvc.post()
                        .uri("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.refreshToken()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh는_새_토큰쌍을_발급하고_구_토큰_재사용_시_전체를_폐기한다() {
        LoginResponse tokens = login("sub-rotate");

        MvcTestResult result = refreshRequest(tokens.refreshToken()).exchange();
        assertThat(result).hasStatusOk();
        TokenResponse rotated = readBody(result, TokenResponse.class);
        assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());

        // 회전된 구 토큰 재사용 → 거부 + 탈취 의심으로 전체 폐기
        assertThat(refreshRequest(tokens.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refreshRequest(rotated.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void access_토큰으로는_refresh할_수_없다() {
        LoginResponse tokens = login("sub-wrong-category");

        // JWT는 refresh 상한(64자)을 넘어 DTO 검증에서 400으로 걸러진다 — 어느 쪽이든 재발급은 불가
        assertThat(refreshRequest(tokens.accessToken())).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 구글이_거부한_ID_토큰이면_401이다() {
        when(googleTokenVerifier.verify("bad-token")).thenThrow(new InvalidOAuthTokenException("검증 실패"));
        UserRegisterResponse device = registerDevice();

        assertThat(mvc.post()
                        .uri("/api/auth/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"idToken\":\"bad-token\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 제거된_login_엔드포인트는_인증_없이_접근하면_401이다() {
        assertThat(mvc.post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"idToken\":\"any\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 상한을_넘는_refresh_토큰은_400이다() {
        // 임의 길이 입력이 해시·조회 로직까지 흘러들지 않게 DTO에서 자른다
        assertThat(refreshRequest("x".repeat(65))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 상한을_넘는_ID_토큰은_400이다() {
        // 프로바이더 검증(외부 HTTP 호출)에 도달하기 전에 DTO에서 자른다
        UserRegisterResponse device = registerDevice();

        assertThat(mvc.post()
                        .uri("/api/auth/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"idToken\":\"" + "e".repeat(8193) + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 다른_유저의_refresh_토큰으로_로그아웃해도_그_토큰은_살아있다() {
        // 소유권 검증 — 인증만 통과한 호출자가 남의 토큰을 폐기할 수 없다
        UserRegisterResponse victim = registerDevice();
        UserRegisterResponse attacker = registerDevice();

        assertThat(logoutRequest(attacker.accessToken(), victim.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);

        // 피해자의 토큰은 여전히 유효하다 (회전 성공)
        assertThat(refreshRequest(victim.refreshToken())).hasStatusOk();
    }

    private void stubVerifier(String sub) {
        when(googleTokenVerifier.verify("id-token-" + sub))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, sub, sub + "@gmail.com"));
    }

    private MockMvcTester.MockMvcRequestBuilder refreshRequest(String refreshToken) {
        return mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private MockMvcTester.MockMvcRequestBuilder logoutRequest(String accessToken, String refreshToken) {
        return mvc.post()
                .uri("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private UserRegisterResponse registerDevice() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus2xxSuccessful();
        return readBody(result, UserRegisterResponse.class);
    }

    private LoginResponse login(String sub) {
        stubVerifier(sub);
        UserRegisterResponse device = registerDevice();
        MvcTestResult result = mvc.post()
                .uri("/api/auth/link")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"GOOGLE\",\"idToken\":\"id-token-" + sub + "\"}")
                .exchange();
        assertThat(result).hasStatusOk();
        return readBody(result, LoginResponse.class);
    }

    // getContentAsString과 달리 getContentAsByteArray는 checked 예외가 없어 테스트에 throws가 안 번진다
    private <T> T readBody(MvcTestResult result, Class<T> type) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }
}
