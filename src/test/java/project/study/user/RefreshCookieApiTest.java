package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.TOKEN_API_VERSION;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import project.study.user.LinkTestClient.Device;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** 브라우저 쿠키 모드에서는 Refresh-Transport 헤더가 있을 때만 refresh가 HttpOnly 쿠키로 오가고, 헤더가 없는 앱 요청은 예전과 같다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshCookieApiTest {

    private static final String TRANSPORT = "Refresh-Transport";
    private static final String COOKIE = "__Host-refresh_token";
    // 테스트 설정 refresh-token.expiration(14일)을 초로
    private static final long MAX_AGE_SECONDS = 1_209_600;
    // 검증 메시지는 요청 언어를 따르므로, 한국어 폰의 앱이 받던 문구와 같은지 본다
    private static final String KOREAN = "ko-KR";

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

    private LinkTestClient api;

    @BeforeEach
    void setUp() {
        api = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers);
    }

    @Test
    void 헤더가_있으면_등록은_refresh를_쿠키로_주고_본문에서_뺀다() {
        MvcTestResult result = registerWithCookie(UUID.randomUUID().toString());

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertIssuedCookie(result);
        assertNoStore(result);
        assertThat(result).bodyJson().doesNotHavePath("$.refreshToken");
        assertThat(result).bodyJson().hasPath("$.accessToken");
    }

    @Test
    void 헤더가_없으면_등록은_본문에_refresh를_주고_쿠키를_굽지_않는다() {
        MvcTestResult result = register(UUID.randomUUID().toString()).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().hasPath("$.refreshToken");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void 쿠키만으로_refresh하면_새_쿠키로_회전되고_옛_쿠키_재사용은_전량_폐기와_쿠키_만료다() {
        MvcTestResult registered = registerWithCookie(UUID.randomUUID().toString());
        String first = cookieValue(registered);
        long userId = api.subOf(accessOf(registered));

        MvcTestResult rotated = refreshWithCookie(first).exchange();
        assertThat(rotated).hasStatusOk();
        assertIssuedCookie(rotated);
        assertNoStore(rotated);
        assertThat(rotated).bodyJson().doesNotHavePath("$.refreshToken");
        assertThat(cookieValue(rotated)).isNotEqualTo(first);

        MvcTestResult reused = refreshWithCookie(first).exchange();
        assertThat(reused)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REFRESH_TOKEN");
        assertExpiredCookie(reused);
        assertThat(refreshTokenCount(userId)).isZero();
    }

    @Test
    void 헤더는_있는데_쿠키도_본문도_없으면_400_VALIDATION_FAILED다() {
        assertBlankRefresh(mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .header(TRANSPORT, "cookie")
                .exchange());
    }

    @Test
    void 헤더_없이_온_쿠키는_읽지_않는다() {
        Device device = api.register();

        assertBlankRefresh(mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .cookie(new Cookie(COOKIE, device.refreshToken()))
                .exchange());
    }

    @Test
    void 본문이_비어도_예전과_같은_400_VALIDATION_FAILED다() {
        assertBlankRefresh(mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"  \"}")
                .exchange());
        assertBlankRefresh(mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange());
    }

    @Test
    void 쿠키_값도_64자_상한을_넘으면_400이다() {
        assertThat(refreshWithCookie("x".repeat(65)).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.message", v -> assertThat(v).isEqualTo("refreshToken: refresh 토큰은 64자 이하여야 합니다"));
    }

    @Test
    void 본문과_쿠키가_둘_다_있으면_본문이_이긴다() {
        Device bodyOwner = api.register();
        Device cookieOwner = api.register();

        MvcTestResult result = refreshWithCookie(cookieOwner.refreshToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + bodyOwner.refreshToken() + "\"}")
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(api.subOf(accessOf(result))).isEqualTo(bodyOwner.userId());
        // 쿠키 주인의 토큰은 건드리지 않았다
        assertThat(api.refresh(cookieOwner.refreshToken())).hasStatusOk();
    }

    @Test
    void 쿠키_모드_로그아웃은_쿠키의_refresh를_폐기하고_쿠키를_만료시킨다() {
        MvcTestResult registered = registerWithCookie(UUID.randomUUID().toString());
        String refresh = cookieValue(registered);
        String access = accessOf(registered);

        MvcTestResult result = mvc.post()
                .uri("/api/auth/logout")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(COOKIE, refresh))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertExpiredCookie(result);
        assertThat(refreshTokenCount(api.subOf(access))).isZero();
    }

    @Test
    void 쿠키_모드_link는_refresh를_쿠키로_주고_본문에서_뺀다() {
        Device device = api.register();

        MvcTestResult result = api.linkRequest(device.accessToken())
                .header(TRANSPORT, "cookie")
                .content(objectMapper.writeValueAsString(new LinkSocialRequest(
                        Provider.GOOGLE, LinkTestClient.freshIdToken(api.stubSocial(Provider.GOOGLE, null)), null)))
                .exchange();

        assertThat(result).hasStatusOk();
        assertIssuedCookie(result);
        assertThat(result).bodyJson().doesNotHavePath("$.refreshToken");
        assertThat(result).bodyJson().extractingPath("$.isNewUser").isEqualTo(true);
    }

    @Test
    void 알_수_없는_쿠키로_refresh하면_401이고_쿠키를_만료시킨다() {
        MvcTestResult result = refreshWithCookie(UUID.randomUUID().toString()).exchange();

        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REFRESH_TOKEN");
        assertExpiredCookie(result);
    }

    @Test
    void 만료된_쿠키로_refresh하면_401이고_쿠키를_만료시킨다() {
        String refresh = cookieValue(registerWithCookie(UUID.randomUUID().toString()));
        jdbc.sql("update refresh_token set expires_at = now() - interval '1 minute' where token_hash = :h")
                .param("h", LinkTestClient.sha256Hex(refresh))
                .update();

        MvcTestResult result = refreshWithCookie(refresh).exchange();

        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.message")
                .isEqualTo("만료된 refresh 토큰입니다");
        assertExpiredCookie(result);
    }

    @Test
    void 쿠키_모드_로그아웃도_토큰이_없으면_400_VALIDATION_FAILED다() {
        Device device = api.register();

        assertBlankRefresh(logoutWithCookieHeader(device.accessToken()).exchange());
    }

    @Test
    void 쿠키_모드_로그아웃도_64자를_넘는_쿠키는_400이다() {
        Device device = api.register();

        assertThat(logoutWithCookieHeader(device.accessToken())
                        .cookie(new Cookie(COOKIE, "x".repeat(65)))
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 헤더_없는_로그아웃은_쿠키를_읽지_않는다() {
        Device device = api.register();

        assertBlankRefresh(mvc.post()
                .uri("/api/auth/logout")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
                .cookie(new Cookie(COOKIE, device.refreshToken()))
                .exchange());
        assertThat(api.refresh(device.refreshToken())).hasStatusOk();
    }

    @Test
    void 로그아웃도_본문과_쿠키가_둘_다_있으면_본문의_토큰을_폐기한다() {
        Device device = api.register();
        MvcTestResult rotated = refreshWithCookie(device.refreshToken()).exchange();
        String cookieToken = cookieValue(rotated);
        Device other = api.register();

        MvcTestResult result = logoutWithCookieHeader(device.accessToken())
                .cookie(new Cookie(COOKIE, cookieToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + other.refreshToken() + "\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        // 본문의 남의 토큰은 내 토큰이 아니라 건드리지 않고, 쿠키 토큰도 본문이 이겨서 그대로 살아 있다
        assertThat(api.refresh(other.refreshToken())).hasStatusOk();
        assertThat(api.refresh(cookieToken)).hasStatusOk();
    }

    @Test
    void 같은_이름의_쿠키가_둘이면_어느_쪽도_고르지_않는다() {
        Device first = api.register();
        Device second = api.register();

        assertBlankRefresh(mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(COOKIE, first.refreshToken()), new Cookie(COOKIE, second.refreshToken()))
                .exchange());
        assertThat(api.refresh(first.refreshToken())).hasStatusOk();
        assertThat(api.refresh(second.refreshToken())).hasStatusOk();
    }

    @Test
    void 헤더_값은_대소문자를_가리지_않지만_정확히_cookie_하나여야_한다() {
        assertThat(register(UUID.randomUUID().toString())
                        .header(TRANSPORT, "COOKIE")
                        .exchange()
                        .getResponse()
                        .getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=");

        for (String[] values :
                List.of(new String[] {"cookie,x"}, new String[] {" cookie"}, new String[] {"cookie", "x"})) {
            MvcTestResult result = register(UUID.randomUUID().toString())
                    .header(TRANSPORT, (Object[]) values)
                    .exchange();

            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
            assertThat(result).bodyJson().hasPath("$.refreshToken");
        }
    }

    private MockMvcTester.MockMvcRequestBuilder register(String deviceId) {
        return mvc.post()
                .uri("/api/users")
                .header(ApiVersionConfig.HEADER, TOKEN_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}");
    }

    private MvcTestResult registerWithCookie(String deviceId) {
        return register(deviceId).header(TRANSPORT, "cookie").exchange();
    }

    private MockMvcTester.MockMvcRequestBuilder refreshWithCookie(String refreshToken) {
        return mvc.post().uri("/api/auth/refresh").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, refreshToken));
    }

    private MockMvcTester.MockMvcRequestBuilder logoutWithCookieHeader(String accessToken) {
        return mvc.post()
                .uri("/api/auth/logout")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.ACCEPT_LANGUAGE, KOREAN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(TRANSPORT, "cookie");
    }

    // refresh 쿠키를 싣는 응답이 중간 캐시에 남지 않게 한다
    private static void assertNoStore(MvcTestResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private void assertIssuedCookie(MvcTestResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header)
                .startsWith(COOKIE + "=")
                .contains("Path=/;", "Max-Age=" + MAX_AGE_SECONDS, "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain");
        assertThat(cookieValue(result)).hasSize(36);
    }

    private void assertExpiredCookie(MvcTestResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;")
                .contains("Path=/;", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict");
    }

    private void assertBlankRefresh(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.message", v -> assertThat(v).isEqualTo("refreshToken: 공백일 수 없습니다"));
    }

    private static String accessOf(MvcTestResult result) {
        return JsonPath.read(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.accessToken");
    }

    private static String cookieValue(MvcTestResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return header.substring(COOKIE.length() + 1, header.indexOf(';'));
    }

    private long refreshTokenCount(long userId) {
        return jdbc.sql("select count(*) from refresh_token where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }
}
