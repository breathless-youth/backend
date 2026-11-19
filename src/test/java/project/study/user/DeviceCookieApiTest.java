package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.TOKEN_API_VERSION;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/**
 * 브라우저 쿠키 모드의 기기 쿠키: 서버가 deviceId를 HttpOnly 쿠키로 들고 있어, 앱처럼 refresh가 만료돼도 같은 비회원으로 돌아온다 (ADR-0050).
 * 헤더가 없는 앱 요청은 이 쿠키를 읽지도 심지도 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeviceCookieApiTest {

    private static final String TRANSPORT = "Refresh-Transport";
    private static final String COOKIE = "__Host-refresh_token";
    private static final String DEVICE_COOKIE = "__Host-device_id";
    // 400일을 초로
    private static final long DEVICE_MAX_AGE_SECONDS = 34_560_000;

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
    void 쿠키_모드_등록은_소문자_deviceId로_기기_쿠키를_심는다() {
        String deviceId = UUID.randomUUID().toString();

        MvcTestResult result = registerWithCookie(deviceId.toUpperCase(Locale.ROOT));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(setCookie(result, DEVICE_COOKIE))
                .startsWith(DEVICE_COOKIE + "=" + deviceId + ";")
                .contains("Path=/;", "Max-Age=" + DEVICE_MAX_AGE_SECONDS, "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain");
    }

    @Test
    void 기기_쿠키가_있으면_본문_deviceId_대신_그_값으로_등록해_같은_비회원으로_돌아온다() {
        String deviceId = UUID.randomUUID().toString();
        long userId = api.subOf(accessOf(registerWithCookie(deviceId)));
        String otherDeviceId = UUID.randomUUID().toString();

        MvcTestResult again = register(otherDeviceId)
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(DEVICE_COOKIE, deviceId.toUpperCase(Locale.ROOT)))
                .exchange();

        assertThat(again).hasStatusOk().bodyJson().extractingPath("$.isNew").isEqualTo(false);
        assertThat(api.subOf(accessOf(again))).isEqualTo(userId);
        assertThat(deviceCookieValue(again)).isEqualTo(deviceId);
        assertThat(deviceUserCount(otherDeviceId)).isZero();
    }

    @Test
    void 형식이_틀린_기기_쿠키는_무시하고_본문_deviceId로_등록한다() {
        String deviceId = UUID.randomUUID().toString();

        MvcTestResult result = register(deviceId)
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(DEVICE_COOKIE, "not-a-uuid"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(deviceCookieValue(result)).isEqualTo(deviceId);
    }

    @Test
    void 같은_이름의_기기_쿠키가_둘이면_고르지_않고_본문_deviceId로_등록한다() {
        String deviceId = UUID.randomUUID().toString();

        MvcTestResult result = register(deviceId)
                .header(TRANSPORT, "cookie")
                .cookie(
                        new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString()),
                        new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(deviceCookieValue(result)).isEqualTo(deviceId);
    }

    @Test
    void refresh는_형식이_틀리거나_둘인_기기_쿠키를_다시_심지_않는다() {
        String refresh = cookieValue(registerWithCookie(UUID.randomUUID().toString()));

        MvcTestResult invalid = refreshWithCookie(refresh)
                .cookie(new Cookie(DEVICE_COOKIE, "not-a-uuid"))
                .exchange();
        assertThat(invalid).hasStatusOk();
        assertThat(setCookie(invalid, DEVICE_COOKIE)).isNull();

        MvcTestResult duplicated = refreshWithCookie(cookieValue(invalid))
                .cookie(
                        new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString()),
                        new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString()))
                .exchange();
        assertThat(duplicated).hasStatusOk();
        assertThat(setCookie(duplicated, DEVICE_COOKIE)).isNull();
    }

    @Test
    void refresh가_실패하면_기기_쿠키가_있어도_수명을_늘리지_않는다() {
        String deviceId = UUID.randomUUID().toString();
        registerWithCookie(deviceId);

        MvcTestResult invalidToken = refreshWithCookie(UUID.randomUUID().toString())
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(invalidToken).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(setCookie(invalidToken, DEVICE_COOKIE)).isNull();

        MvcTestResult noRefreshCookie = mvc.post()
                .uri("/api/auth/refresh")
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(noRefreshCookie).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(setCookie(noRefreshCookie, DEVICE_COOKIE)).isNull();
    }

    @Test
    void 기기_쿠키가_있어도_본문_deviceId_검증은_그대로다() {
        assertThat(register("not-a-uuid")
                        .header(TRANSPORT, "cookie")
                        .cookie(new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString()))
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 쿠키_모드_refresh는_기기_쿠키가_있을_때만_수명을_다시_늘린다() {
        String deviceId = UUID.randomUUID().toString();
        String refresh = cookieValue(registerWithCookie(deviceId));

        MvcTestResult renewed = refreshWithCookie(refresh)
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(renewed).hasStatusOk();
        assertThat(setCookie(renewed, DEVICE_COOKIE))
                .startsWith(DEVICE_COOKIE + "=" + deviceId + ";")
                .contains("Max-Age=" + DEVICE_MAX_AGE_SECONDS);

        MvcTestResult withoutDevice = refreshWithCookie(cookieValue(renewed)).exchange();
        assertThat(withoutDevice).hasStatusOk();
        assertThat(setCookie(withoutDevice, DEVICE_COOKIE)).isNull();
    }

    @Test
    void 쿠키_모드가_아니면_기기_쿠키를_읽지도_심지도_않는다() {
        String deviceId = UUID.randomUUID().toString();
        long userId = api.subOf(accessOf(registerWithCookie(deviceId)));

        MvcTestResult registered = register(UUID.randomUUID().toString())
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(registered).hasStatus(HttpStatus.CREATED);
        assertThat(api.subOf(accessOf(registered))).isNotEqualTo(userId);
        assertThat(registered.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        MvcTestResult refreshed = mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\""
                        + api.read(registered, UserRegisterResponse.class).refreshToken() + "\"}")
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(refreshed).hasStatusOk();
        assertThat(refreshed.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void 로그아웃은_기기_쿠키를_지우지_않고_다시_등록하면_같은_비회원이다() {
        String deviceId = UUID.randomUUID().toString();
        MvcTestResult registered = registerWithCookie(deviceId);
        long userId = api.subOf(accessOf(registered));

        MvcTestResult loggedOut = logoutWithCookieHeader(accessOf(registered))
                .cookie(new Cookie(COOKIE, cookieValue(registered)), new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
        assertThat(loggedOut).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(setCookie(loggedOut, DEVICE_COOKIE)).isNull();

        MvcTestResult again = registerWithDeviceCookie(deviceId);
        assertThat(again).hasStatusOk();
        assertThat(api.subOf(accessOf(again))).isEqualTo(userId);
    }

    @Test
    void 소셜_전환하고_로그아웃한_뒤_같은_기기_쿠키로_등록하면_회원이_아니라_새_비회원이다() {
        String deviceId = UUID.randomUUID().toString();
        String guestAccess = accessOf(registerWithCookie(deviceId));
        long guestId = api.subOf(guestAccess);

        MvcTestResult linked = linkWithCookie(guestAccess, api.stubSocial(Provider.GOOGLE, null));
        assertThat(linked).bodyJson().extractingPath("$.isNewUser").isEqualTo(true);
        long memberId = api.subOf(accessOf(linked));
        assertThat(memberId).isEqualTo(guestId);
        assertThat(logoutWithCookieHeader(accessOf(linked))
                        .cookie(new Cookie(COOKIE, cookieValue(linked)), new Cookie(DEVICE_COOKIE, deviceId))
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult again = registerWithDeviceCookie(deviceId);

        assertThat(again).hasStatus(HttpStatus.CREATED);
        assertThat(api.subOf(accessOf(again))).isNotEqualTo(memberId);
    }

    @Test
    void 병합한_뒤_같은_기기_쿠키로_등록하면_회원이_아니라_새_비회원이다() {
        Device other = api.register();
        LinkTestClient.Social social = api.stubSocial(Provider.GOOGLE, null);
        api.linkOk(other, social);
        long memberId = other.userId();
        String deviceId = UUID.randomUUID().toString();
        String guestAccess = accessOf(registerWithCookie(deviceId));
        long guestId = api.subOf(guestAccess);

        MvcTestResult merged = linkWithCookie(guestAccess, social);
        assertThat(merged).bodyJson().extractingPath("$.isNewUser").isEqualTo(false);
        assertThat(api.subOf(accessOf(merged))).isEqualTo(memberId);

        MvcTestResult again = registerWithDeviceCookie(deviceId);

        assertThat(again).hasStatus(HttpStatus.CREATED);
        assertThat(api.subOf(accessOf(again))).isNotIn(memberId, guestId);
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

    // 본문에는 매번 새 UUID를 실어, 등록이 기기 쿠키의 값으로 됐는지 보이게 한다
    private MvcTestResult registerWithDeviceCookie(String deviceId) {
        return register(UUID.randomUUID().toString())
                .header(TRANSPORT, "cookie")
                .cookie(new Cookie(DEVICE_COOKIE, deviceId))
                .exchange();
    }

    private MockMvcTester.MockMvcRequestBuilder refreshWithCookie(String refreshToken) {
        return mvc.post().uri("/api/auth/refresh").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, refreshToken));
    }

    private MockMvcTester.MockMvcRequestBuilder logoutWithCookieHeader(String accessToken) {
        return mvc.post()
                .uri("/api/auth/logout")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(TRANSPORT, "cookie");
    }

    private MvcTestResult linkWithCookie(String accessToken, LinkTestClient.Social social) {
        MvcTestResult result = api.linkRequest(accessToken)
                .header(TRANSPORT, "cookie")
                .content(objectMapper.writeValueAsString(
                        new LinkSocialRequest(social.provider(), LinkTestClient.freshIdToken(social), null)))
                .exchange();
        assertThat(result).hasStatusOk();
        return result;
    }

    private static String accessOf(MvcTestResult result) {
        return JsonPath.read(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.accessToken");
    }

    private static String setCookie(MvcTestResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    private static String cookieValue(MvcTestResult result) {
        return valueOf(setCookie(result, COOKIE), COOKIE);
    }

    private static String deviceCookieValue(MvcTestResult result) {
        return valueOf(setCookie(result, DEVICE_COOKIE), DEVICE_COOKIE);
    }

    private static String valueOf(String header, String name) {
        return header.substring(name.length() + 1, header.indexOf(';'));
    }

    private long deviceUserCount(String deviceId) {
        return jdbc.sql("select count(*) from users where provider = 'DEVICE' and provider_user_id = :d")
                .param("d", deviceId)
                .query(Long.class)
                .single();
    }
}
