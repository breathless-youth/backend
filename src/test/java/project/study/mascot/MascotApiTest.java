package project.study.mascot;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MascotApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Test
    void 처음에는_첫_마스코트만_열려_있고_선택돼_있다() {
        long user = fixture.member();

        assertThat(list(user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("BOOK"))
                .hasPathSatisfying("$.mascots.length()", v -> assertThat(v).isEqualTo(7))
                .hasPathSatisfying("$.mascots[0].unlocked", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.mascots[0].newlyUnlocked", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.mascots[1].unlocked", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying(
                        "$.mascots[1].remainingFocusSec", v -> assertThat(v).isEqualTo(18000));
    }

    @Test
    void 누적_순공이_기준을_넘으면_열리고_축하는_한_번만_알린다() {
        long user = fixture.member();
        fixture.measuredSession(user, "2026-01-10", 1, 18000);
        // 직접 입력한 기록은 누적 순공에 넣지 않는다
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec, source)
                VALUES (:u, DATE '2026-01-11', TIMESTAMPTZ '2026-01-11T01:00:00Z', TIMESTAMPTZ '2026-01-11T21:00:00Z', 72000, 72000, 'MANUAL')""").param("u", user).update();

        assertThat(list(user))
                .bodyJson()
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(18000))
                .hasPathSatisfying("$.mascots[1].unlocked", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.mascots[1].newlyUnlocked", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.mascots[2].unlocked", v -> assertThat(v).isEqualTo(false));

        assertThat(post(user, "/api/mascots/unlocks/seen"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.mascots[1].newlyUnlocked", v -> assertThat(v).isEqualTo(false));
        assertThat(post(user, "/api/mascots/unlocks/seen")).hasStatusOk();
    }

    @Test
    void 열린_마스코트만_선택할_수_있다() {
        long user = fixture.member();
        fixture.measuredSession(user, "2026-01-10", 1, 18000);

        assertThat(select(user, "PLANNER"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("PLANNER"))
                .hasPathSatisfying("$.mascots[1].selected", v -> assertThat(v).isEqualTo(true));
        assertThat(select(user, "LAMP"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("MASCOT_LOCKED"));
        assertThat(select(user, "NOPE"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("MASCOT_NOT_FOUND"));
        assertThat(list(fixture.guest())).hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult list(long user) {
        return mvc.get()
                .uri("/api/mascots")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }

    private MvcTestResult post(long user, String uri) {
        return mvc.post()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }

    private MvcTestResult select(long user, String code) {
        return mvc.put()
                .uri("/api/mascots/selected")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();
    }
}
