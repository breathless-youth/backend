package project.study.premiumwaitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.premiumwaitlist.service.PremiumWaitlistUserDataHandler;

/** BY-1394 프리미엄 사전 신청 저장 API — upsert·검증·개인정보 규칙을 다룬다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PremiumWaitlistApiTest {

    private static final String URI = "/api/premium-waitlist/me";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PremiumWaitlistUserDataHandler handler;

    private long userId;

    @BeforeEach
    void seedUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('DEVICE', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "pw-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM premium_waitlist");
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private MvcTestResult put(String body) {
        return mvc.put()
                .uri(URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private static String body(int price, boolean over14, String email, String feature) {
        return """
                {"price":%d,"placement":"RESULT","ageOver14":%s,"email":%s,"expectedFeature":%s}""".formatted(
                        price,
                        over14,
                        email == null ? "null" : "\"" + email + "\"",
                        feature == null ? "null" : "\"" + feature + "\"");
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM premium_waitlist WHERE user_id = ?", userId);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM premium_waitlist WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 저장하면_204이고_행이_생긴다() {
        assertThat(put(body(2900, true, "a@example.com", null))).hasStatus(HttpStatus.NO_CONTENT);

        Map<String, Object> row = row();
        assertThat(row.get("price")).isEqualTo(2900);
        assertThat(row.get("placement")).isEqualTo("RESULT");
        assertThat(row.get("age_over14")).isEqualTo(true);
        assertThat(row.get("email")).isEqualTo("a@example.com");
        assertThat(row.get("expected_feature")).isNull();
        assertThat(row.get("consented_at")).isNotNull();
    }

    @Test
    void 같은_사람이_다시_저장하면_한_행으로_덮어쓰고_기대_기능이_붙는다() {
        put(body(2900, true, "a@example.com", null));
        Object consentedAt = row().get("consented_at");

        assertThat(put(body(2900, true, "a@example.com", "AI_PLANNER"))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rows()).isEqualTo(1);
        assertThat(row().get("expected_feature")).isEqualTo("AI_PLANNER");
        assertThat(row().get("consented_at")).isEqualTo(consentedAt);
    }

    @Test
    void 기대_기능이_null로_오면_기존_값을_유지한다() {
        put(body(5900, true, "a@example.com", "AI_REPORT"));

        assertThat(put(body(5900, true, "a@example.com", null))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(row().get("expected_feature")).isEqualTo("AI_REPORT");
    }

    @Test
    void 강력한_앱_잠금을_기대_기능으로_저장한다() {
        assertThat(put(body(2900, true, "a@example.com", "APP_LOCK"))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(row().get("expected_feature")).isEqualTo("APP_LOCK");
    }

    @Test
    void 허용되지_않은_가격은_400이다() {
        assertThat(put(body(4900, true, "a@example.com", null))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(rows()).isZero();
    }

    @Test
    void 허용되지_않은_기대_기능은_400이다() {
        assertThat(put(body(2900, true, "a@example.com", "NOPE"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 열네_살_이상인데_이메일이_없으면_400이다() {
        assertThat(put(body(2900, true, null, null))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(rows()).isZero();
    }

    @Test
    void 이메일_형식이_틀리면_400이다() {
        assertThat(put(body(2900, true, "not-an-email", null))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이메일이_254자를_넘으면_400이다() {
        String longEmail = "a".repeat(250) + "@example.com";

        assertThat(put(body(2900, true, longEmail, null))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 열네_살_미만은_이메일_없이_204이고_이메일과_동의_시각은_null이다() {
        assertThat(put(body(2900, false, null, null))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(row().get("email")).isNull();
        assertThat(row().get("consented_at")).isNull();
        assertThat(row().get("age_over14")).isEqualTo(false);
    }

    @Test
    void 열네_살_미만이_이메일을_보내도_저장하지_않는다() {
        assertThat(put(body(2900, false, "kid@example.com", null))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(row().get("email")).isNull();
        assertThat(row().get("consented_at")).isNull();
    }

    @Test
    void 토큰_없이_부르면_401이다() {
        MvcTestResult result = mvc.put()
                .uri(URI)
                .header("API-Version", "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(2900, true, "a@example.com", null))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 탈퇴_처리기가_행을_지운다() {
        put(body(2900, true, "a@example.com", null));
        assertThat(rows()).isEqualTo(1);

        handler.erase(userId);

        assertThat(rows()).isZero();
    }
}
