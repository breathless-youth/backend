package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static project.study.support.AuthTestSupport.asUser;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.service.AdMobKeySource;

/** AdMob 광고 보상 콜백. 구글 대신 테스트 키로 서명한다. 콜백은 토큰도 API-Version 헤더도 없이 온다. */
@SpringBootTest(properties = {"app.points.ad.units=ca-app-pub-123/2747237135", "app.points.ad.points=10"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdRewardApiTest {

    private static final String UNIT = "2747237135";
    private static final AdMobSigner SIGNER = new AdMobSigner();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @MockitoBean
    AdMobKeySource keySource;

    @BeforeEach
    void keys() {
        given(keySource.fetch()).willReturn(Map.of(AdMobSigner.KEY_ID, SIGNER.publicKey()));
    }

    @Test
    void 서명이_맞는_콜백은_토큰_없이_포인트를_주고_같은_콜백은_한_번만_들어간다() {
        long user = fixture.member();
        String query = SIGNER.signed(SIGNER.content(UNIT, String.valueOf(user), tx()));

        assertThat(callback(query)).hasStatusOk();
        assertThat(callback(query)).hasStatusOk();

        assertThat(balance(user)).isEqualTo(10);
        assertThat(earn(user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.ad.available", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.ad.points", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.ad.watchedToday", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.ad.dailyLimit", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.todayEarned", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.dailyMax", v -> assertThat(v).isEqualTo(240 + 10 + 30));
        assertThat(mvc.get()
                        .uri("/api/points/history")
                        .with(asUser(user))
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[0].reason", v -> assertThat(v).isEqualTo("AD"))
                .hasPathSatisfying("$.items[0].amount", v -> assertThat(v).isEqualTo(10));
    }

    @Test
    void 하루_세_번까지만_준다() {
        long user = fixture.member();

        for (int i = 0; i < 4; i++) {
            assertThat(callback(SIGNER.signed(SIGNER.content(UNIT, String.valueOf(user), tx()))))
                    .hasStatusOk();
        }

        assertThat(balance(user)).isEqualTo(30);
        assertThat(earn(user))
                .bodyJson()
                .hasPathSatisfying("$.ad.watchedToday", v -> assertThat(v).isEqualTo(3));
    }

    @Test
    void 서명이_틀리면_400이고_주지_않는다() {
        long user = fixture.member();
        long other = fixture.member();
        String query = SIGNER.signed(SIGNER.content(UNIT, String.valueOf(user), tx()));

        assertThat(callback(query.replace("user_id=" + user, "user_id=" + other)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(callback(new AdMobSigner().signed(SIGNER.content(UNIT, String.valueOf(user), tx()))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(callback(SIGNER.content(UNIT, String.valueOf(user), tx()))).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(balance(user)).isZero();
        assertThat(balance(other)).isZero();
    }

    @Test
    void 다른_앱의_광고_단위로_받은_서명은_주지_않는다() {
        long user = fixture.member();

        assertThat(callback(SIGNER.signed(SIGNER.content("9999999999", String.valueOf(user), tx()))))
                .hasStatusOk();

        assertThat(balance(user)).isZero();
    }

    @Test
    void 비회원과_없는_사용자와_숫자가_아닌_사용자는_주지_않고_200이다() {
        long guest = fixture.guest();

        assertThat(callback(SIGNER.signed(SIGNER.content(UNIT, String.valueOf(guest), tx()))))
                .hasStatusOk();
        assertThat(callback(SIGNER.signed(SIGNER.content(UNIT, "999999999999", tx()))))
                .hasStatusOk();
        assertThat(callback(SIGNER.signed(SIGNER.content(UNIT, "abc", tx())))).hasStatusOk();
        assertThat(callback(SIGNER.signed(SIGNER.content(UNIT, "", tx())))).hasStatusOk();

        assertThat(balance(guest)).isZero();
    }

    @Test
    void 콜백_말고_다른_포인트_경로는_여전히_토큰이_필요하다() {
        assertThat(mvc.get()
                        .uri("/api/points/earn")
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/points/ads/admob/callback").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private static String tx() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MvcTestResult callback(String query) {
        return mvc.get().uri("/api/points/ads/admob/callback?" + query).exchange();
    }

    private MvcTestResult earn(long user) {
        return mvc.get()
                .uri("/api/points/earn")
                .with(asUser(user))
                .header(ApiVersionConfig.HEADER, "1")
                .exchange();
    }

    private int balance(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }
}
