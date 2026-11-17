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
import project.study.mascot.service.MascotService;
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

    @Autowired
    MascotService mascots;

    @Test
    void 처음에는_첫_마스코트만_열려_있고_선택돼_있다() {
        long user = fixture.member();

        assertThat(list(user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("STOPWATCH"))
                .hasPathSatisfying("$.mascots.length()", v -> assertThat(v).isEqualTo(7))
                .extractingPath("$.mascots[*].code")
                .asArray()
                .containsExactly("STOPWATCH", "PENCIL", "BOOK", "LOCK", "PLANNER", "LAMP", "CAMCORDER");
        assertThat(list(user))
                .bodyJson()
                .hasPathSatisfying("$.mascots[6].unlockType", v -> assertThat(v).isEqualTo("POINTS"))
                .hasPathSatisfying("$.mascots[6].price", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying(
                        "$.mascots[4].unlockFocusSec", v -> assertThat(v).isEqualTo(180000))
                .hasPathSatisfying("$.mascots[0].unlocked", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.mascots[0].newlyUnlocked", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.mascots[1].unlocked", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying(
                        "$.mascots[1].remainingFocusSec", v -> assertThat(v).isEqualTo(3600));
    }

    @Test
    void 누적_순공이_기준을_넘으면_열리고_축하는_한_번만_알린다() {
        long user = fixture.member();
        fixture.measuredSession(user, "2026-01-10", 1, 7200);
        // 직접 입력한 기록은 누적 순공에 넣지 않는다
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec, source)
                VALUES (:u, DATE '2026-01-11', TIMESTAMPTZ '2026-01-11T01:00:00Z', TIMESTAMPTZ '2026-01-11T21:00:00Z', 72000, 72000, 'MANUAL')""").param("u", user).update();

        assertThat(list(user))
                .bodyJson()
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(7200))
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
        fixture.measuredSession(user, "2026-01-10", 1, 7200);

        assertThat(select(user, "PENCIL"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("PENCIL"))
                .hasPathSatisfying("$.mascots[1].selected", v -> assertThat(v).isEqualTo(true));
        assertThat(select(user, "LAMP"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("MASCOT_LOCKED"));
        assertThat(select(user, "NOPE"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("MASCOT_NOT_FOUND"));
        long guest = fixture.guest();
        assertThat(list(guest)).hasStatusOk();
        assertThat(select(guest, "STOPWATCH")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void 포인트로_사는_마스코트는_사야_열리고_한_번만_살_수_있다() {
        long user = fixture.member();
        fixture.givePoints(user, 500);
        // 누적 순공이 아무리 많아도 사기 전에는 열리지 않는다
        fixture.measuredSession(user, "2026-01-10", 1, 600000);
        jdbc.sql("UPDATE mascot SET price = 300 WHERE code = 'CAMCORDER'").update();
        try {
            assertThat(list(user))
                    .bodyJson()
                    .hasPathSatisfying(
                            "$.mascots[6].unlockType", v -> assertThat(v).isEqualTo("POINTS"))
                    .hasPathSatisfying("$.mascots[6].price", v -> assertThat(v).isEqualTo(300))
                    .hasPathSatisfying(
                            "$.mascots[6].unlocked", v -> assertThat(v).isEqualTo(false))
                    .hasPathSatisfying(
                            "$.mascots[5].unlockType", v -> assertThat(v).isEqualTo("FOCUS_TIME"))
                    .hasPathSatisfying("$.mascots[5].price", v -> assertThat(v).isNull());
            assertCode(select(user, "CAMCORDER"), "MASCOT_LOCKED");
            assertCode(post(user, "/api/mascots/LAMP/purchase"), "MASCOT_NOT_PURCHASABLE");

            assertThat(post(user, "/api/mascots/CAMCORDER/purchase"))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(200));
            assertCode(post(user, "/api/mascots/CAMCORDER/purchase"), "MASCOT_ALREADY_OWNED");
            assertThat(select(user, "CAMCORDER"))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying(
                            "$.mascots[6].unlocked", v -> assertThat(v).isEqualTo(true))
                    .hasPathSatisfying(
                            "$.mascots[6].newlyUnlocked", v -> assertThat(v).isEqualTo(false));
            assertThat(mvc.get()
                            .uri("/api/points/history?type=SPENT")
                            .header(ApiVersionConfig.HEADER, "1")
                            .with(asUser(user))
                            .exchange())
                    .bodyJson()
                    .hasPathSatisfying("$.items[0].reason", v -> assertThat(v).isEqualTo("MASCOT_PURCHASE"))
                    .hasPathSatisfying("$.items[0].label", v -> assertThat(v).isEqualTo("CAMCORDER"))
                    .hasPathSatisfying("$.items[0].amount", v -> assertThat(v).isEqualTo(-300));

            long poor = fixture.member();
            assertCode(post(poor, "/api/mascots/CAMCORDER/purchase"), "INSUFFICIENT_POINTS");
        } finally {
            jdbc.sql("DELETE FROM user_mascot WHERE selected_mascot_code = 'CAMCORDER' AND user_id = :u")
                    .param("u", user)
                    .update();
            jdbc.sql("UPDATE mascot SET price = 1000 WHERE code = 'CAMCORDER'").update();
        }
    }

    @Test
    void 고른_마스코트가_포인트로_사는_것으로_바뀌면_사지_않은_사람은_첫_마스코트로_돌아간다() {
        long user = fixture.member();
        fixture.measuredSession(user, "2026-01-10", 1, 7200);
        assertThat(select(user, "PENCIL")).hasStatusOk();
        jdbc.sql("UPDATE mascot SET price = 300 WHERE code = 'PENCIL'").update();
        try {
            assertThat(list(user))
                    .bodyJson()
                    .hasPathSatisfying(
                            "$.selectedMascotCode", v -> assertThat(v).isEqualTo("STOPWATCH"))
                    .hasPathSatisfying(
                            "$.mascots[1].unlocked", v -> assertThat(v).isEqualTo(false));
            assertThat(mascots.selectedCodes(java.util.List.of(user))).containsEntry(user, "STOPWATCH");
        } finally {
            jdbc.sql("UPDATE mascot SET price = NULL WHERE code = 'PENCIL'").update();
        }
    }

    private static void assertCode(MvcTestResult result, String code) {
        assertThat(result)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
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
