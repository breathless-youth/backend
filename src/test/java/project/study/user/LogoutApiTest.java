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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** 기기별 로그아웃 — 호출한 기기의 refresh만 지우고, 남의 토큰은 건드리지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LogoutApiTest {

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

    private MvcTestResult logout(String accessToken, String refreshToken) {
        return mvc.post()
                .uri("/api/auth/logout")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")
                .exchange();
    }

    /** 같은 소셜 계정에 기기 둘을 붙인다 — 첫 기기는 전환, 둘째는 병합이라 둘 다 같은 userId의 토큰 쌍을 받는다. */
    private LinkResponse[] twoDevicesOfOneAccount() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        LinkResponse first = api.linkOk(api.register(), google);
        LinkResponse second = api.linkOk(api.register(), google);
        assertThat(api.subOf(second.accessToken())).isEqualTo(api.subOf(first.accessToken()));
        return new LinkResponse[] {first, second};
    }

    @Test
    void 로그아웃하면_이_기기_refresh만_무효가_된다() {
        LinkResponse[] devices = twoDevicesOfOneAccount();

        assertThat(logout(devices[0].accessToken(), devices[0].refreshToken())).hasStatus(204);

        assertThat(api.refresh(devices[0].refreshToken())).hasStatus(401);
        assertThat(api.refresh(devices[1].refreshToken())).hasStatusOk();
    }

    @Test
    void 남의_refresh로_로그아웃해도_그_사람은_로그아웃되지_않는다() {
        Device mine = api.register();
        Device other = api.register();

        assertThat(logout(mine.accessToken(), other.refreshToken())).hasStatus(204);

        assertThat(api.refresh(other.refreshToken())).hasStatusOk();
        assertThat(api.refresh(mine.refreshToken())).hasStatusOk();
    }

    @Test
    void 이미_회전된_내_refresh로_로그아웃하면_전량_폐기된다() {
        LinkResponse[] devices = twoDevicesOfOneAccount();
        assertThat(api.refresh(devices[0].refreshToken())).hasStatusOk(); // 첫 기기의 옛 토큰은 이제 tombstone

        assertThat(logout(devices[0].accessToken(), devices[0].refreshToken())).hasStatus(204);

        long userId = api.subOf(devices[0].accessToken());
        assertThat(jdbc.sql("select count(*) from refresh_token where user_id = :u")
                        .param("u", userId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(api.refresh(devices[1].refreshToken())).hasStatus(401);
    }

    @Test
    void 없는_토큰도_204다() {
        Device device = api.register();

        assertThat(logout(device.accessToken(), UUID.randomUUID().toString())).hasStatus(204);

        assertThat(api.refresh(device.refreshToken())).hasStatusOk();
    }
}
