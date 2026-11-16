package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;

/** 스토어 심사용 로그인 (BY-1300). 토큰 없이 부른다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReviewLoginApiTest {

    private static final String PASSWORD = "review-pass-1";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    private String itemCode;

    @BeforeEach
    void setUp() {
        long item = fixture.pricedItem(null, 100);
        itemCode = jdbc.sql("SELECT code FROM shop_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single();
        // 다른 테스트가 만든 심사 계정이 남아 있으면 탈퇴시켜 처음부터 시작한다
        jdbc.sql("SELECT id FROM users WHERE provider = 'REVIEW'")
                .query(Long.class)
                .list()
                .forEach(id -> assertThat(mvc.delete()
                                .uri("/api/users/me")
                                .header(ApiVersionConfig.HEADER, "1")
                                .with(asUser(id)))
                        .hasStatus(HttpStatus.NO_CONTENT));
        jdbc.sql("DELETE FROM review_login_failure").update();
        jdbc.sql("DELETE FROM review_login").update();
        jdbc.sql("""
                INSERT INTO review_login (id, login_id, password_hash, released_version, initial_points,
                    initial_item_codes)
                VALUES (1, 'review', :hash, '26.46.0', 5000, :codes)""")
                .param("hash", new BCryptPasswordEncoder().encode(PASSWORD))
                .param("codes", itemCode + ", NO_SUCH_ITEM")
                .update();
    }

    @Test
    void 출시된_버전보다_높은_앱에서만_켜지고_설정이_없으면_꺼져_있다() {
        assertThat(status("26.47.0"))
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(true));
        assertThat(status("26.46.0"))
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(false));
        assertThat(status("26.9.9"))
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(false));
        assertThat(status("abc"))
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(false));
        assertCode(login("review", PASSWORD, "26.46.0", "10.0.0.1"), HttpStatus.FORBIDDEN, "REVIEW_LOGIN_DISABLED");

        jdbc.sql("UPDATE review_login SET released_version = NULL").update();
        assertThat(status("99.0.0"))
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(false));
        jdbc.sql("DELETE FROM review_login").update();
        assertThat(status("99.0.0"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> assertThat(v).isEqualTo(false));
        assertCode(login("review", PASSWORD, "99.0.0", "10.0.0.1"), HttpStatus.FORBIDDEN, "REVIEW_LOGIN_DISABLED");
    }

    @Test
    void 로그인하면_토큰을_받고_심사_계정은_회원이며_처음_상태는_한_번만_채운다() {
        MvcTestResult first = login("review", PASSWORD, "26.47.0", "10.0.0.1");
        assertThat(first)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.accessToken", v -> assertThat(v).asString().isNotBlank())
                .hasPathSatisfying(
                        "$.refreshToken", v -> assertThat(v).asString().isNotBlank());
        assertThat(login("review", PASSWORD, "26.47.0", "10.0.0.2")).hasStatusOk();

        long user = reviewUser();
        assertThat(balanceOf(user)).isEqualTo(5000);
        assertThat(ownedCodes(user)).isEqualTo(1);
        // 세트를 모두 찾을 수 있게 마스코트 7종이 다 열려 있고, 열림 축하는 뜨지 않는다
        assertThat(mvc.get()
                        .uri("/api/mascots")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.mascots[*].unlocked",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                .hasSize(7)
                                .containsOnly(true))
                .hasPathSatisfying(
                        "$.mascots[*].remainingFocusSec",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                .containsOnly(0))
                .hasPathSatisfying(
                        "$.mascots[*].newlyUnlocked",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                .containsOnly(false));
        assertThat(mvc.put()
                        .uri("/api/mascots/selected")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CAMCORDER\"}"))
                .hasStatusOk();
        // 회원이라 받기도 된다
        assertThat(mvc.post()
                        .uri("/api/points/rewards/attendance/claim")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user)))
                .hasStatusOk();
    }

    @Test
    void 계정을_삭제한_뒤_다시_로그인하면_새_계정을_처음_상태로_만든다() {
        login("review", PASSWORD, "26.47.0", "10.0.0.1");
        long before = reviewUser();
        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(before)))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(login("review", PASSWORD, "26.47.0", "10.0.0.1")).hasStatusOk();

        long after = reviewUser();
        assertThat(after).isNotEqualTo(before);
        assertThat(balanceOf(after)).isEqualTo(5000);
        assertThat(ownedCodes(after)).isEqualTo(1);
    }

    @Test
    void 틀린_시도는_IP별로_막고_다른_IP의_심사자는_막지_않는다() {
        for (int i = 0; i < 5; i++) {
            assertCode(
                    login("review", "wrong", "26.47.0", "203.0.113.9, 10.0.0.9"),
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REVIEW_CREDENTIALS");
        }
        assertCode(
                login("nobody", PASSWORD, "26.47.0", "10.0.0.8"), HttpStatus.BAD_REQUEST, "INVALID_REVIEW_CREDENTIALS");

        // 맨 끝 주소(프록시가 붙인 값)가 기준이다. 앞쪽 값을 바꿔도 풀리지 않는다
        assertCode(
                login("review", PASSWORD, "26.47.0", "198.51.100.1, 10.0.0.9"),
                HttpStatus.TOO_MANY_REQUESTS,
                "TOO_MANY_REQUESTS");
        assertThat(login("review", PASSWORD, "26.47.0", "10.0.0.7")).hasStatusOk();
    }

    private MvcTestResult status(String appVersion) {
        return mvc.get()
                .uri("/api/auth/review-login/status?appVersion={v}", appVersion)
                .header(ApiVersionConfig.HEADER, "1")
                .exchange();
    }

    private MvcTestResult login(String loginId, String password, String appVersion, String forwardedFor) {
        return mvc.post()
                .uri("/api/auth/review-login")
                .header(ApiVersionConfig.HEADER, "1")
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"loginId":"%s","password":"%s","appVersion":"%s"}""".formatted(loginId, password, appVersion))
                .exchange();
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    private long reviewUser() {
        return jdbc.sql("SELECT id FROM users WHERE provider = 'REVIEW'")
                .query(Long.class)
                .single();
    }

    private int balanceOf(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    private int ownedCodes(long user) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM user_item ui JOIN shop_item si ON si.id = ui.item_id
                        WHERE ui.user_id = :u AND si.code = :c""")
                .param("u", user)
                .param("c", itemCode)
                .query(Integer.class)
                .single();
    }
}
