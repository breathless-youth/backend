package project.study.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HomeAccessoryMissionApiTest {
    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Test
    void 이전에_포인트로_소품을_산_회원은_완료를_눌러_300점을_한_번만_받는다() {
        long user = fixture.member();
        String mission = "/api/home/mission";
        String finish = mission + "/steps/3/complete?includeAccessory=true";
        jdbc.sql("INSERT INTO home_mission_progress VALUES (:u, 'home-v3', 2)")
                .param("u", user)
                .update();
        assertThat(post(user, mission + "/rewards/claim"))
                .bodyJson()
                .hasPathSatisfying("$.points", v -> assertThat(v).isEqualTo(150));
        long background = fixture.pricedItem(null, 50);
        assertThat(post(user, "/api/shop/items/" + background + "/purchase")).hasStatusOk();
        assertThat(post(user, finish)).hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        long accessory = fixture.pricedItem("STOPWATCH", 100);
        assertThat(post(user, "/api/shop/items/" + accessory + "/purchase")).hasStatusOk();
        assertThat(mvc.get()
                        .uri(mission + "?includeAccessory=true")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.currentStep", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.eligible", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.claimablePoints", v -> assertThat(v).isEqualTo(0));
        assertThat(post(user, finish))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.completedSteps", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.rewardedPoints", v -> assertThat(v).isEqualTo(300));
        assertThat(jdbc.sql("""
                SELECT completed_steps
                FROM home_mission_progress
                WHERE user_id = :user AND mission_version = 'home-v3'
                """).param("user", user).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(post(user, finish))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.rewardedPoints", v -> assertThat(v).isEqualTo(0));
        assertThat(mvc.get()
                        .uri(mission)
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.completedSteps", v -> assertThat(v).isEqualTo(2));
    }

    private MvcTestResult post(long user, String uri) {
        return mvc.post()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }
}
