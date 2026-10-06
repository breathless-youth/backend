package project.study.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.support.AuthTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// app_config는 컨테이너 DB를 다른 테스트와 공유한다 — 바꾼 값은 테스트 트랜잭션과 함께 롤백한다
@Transactional
class InterviewStatusApiTest {

    private static final String CARD_URL = "https://forms.example/g3_complete";
    private static final String SETTINGS_URL = "https://forms.example/settings";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
        config(InterviewConfigKeys.ENABLED, "true");
        config(InterviewConfigKeys.CARD_ENABLED, "true");
        config(InterviewConfigKeys.CARD_URL, CARD_URL);
        config(InterviewConfigKeys.SETTINGS_ENABLED, "true");
        config(InterviewConfigKeys.SETTINGS_URL, SETTINGS_URL);
    }

    private void config(String key, String value) {
        jdbcTemplate.update("UPDATE app_config SET config_value = ? WHERE config_key = ?", value, key);
    }

    /** 최근 완료 세션 3건 — 3번 그룹 */
    private void makeActive() {
        Instant now = Instant.now();
        for (int i = 1; i <= 3; i++) {
            Instant started = now.minus(Duration.ofHours(i * 2L));
            jdbcTemplate.update(
                    "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                            + " study_sec, focus_sec) VALUES (?, CURRENT_DATE, ?, ?, ?, 600, 600)",
                    userId,
                    Timestamp.from(started),
                    Timestamp.from(started),
                    Timestamp.from(started.plus(Duration.ofHours(1))));
        }
    }

    private MockMvcTester.MockMvcRequestBuilder status() {
        return mvc.get()
                .uri("/api/interview/status")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(AuthTestSupport.asUser(userId));
    }

    @Test
    void 그룹3이면_카드와_설정_링크를_내려준다() {
        makeActive();

        assertThat(status()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"cardEligible": true, "cardUrl": "%s", "settingsEnabled": true, "settingsUrl": "%s"}""".formatted(CARD_URL, SETTINGS_URL));
    }

    @Test
    void 그룹3이_아니면_카드는_없고_설정은_보인다() {
        assertThat(status()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"cardEligible": false, "cardUrl": null, "settingsEnabled": true, "settingsUrl": "%s"}""".formatted(SETTINGS_URL));
    }

    @Test
    void 인터뷰_전체를_끄면_모두_꺼진다() {
        makeActive();
        config(InterviewConfigKeys.ENABLED, "false");

        assertThat(status()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"cardEligible": false, "cardUrl": null, "settingsEnabled": false, "settingsUrl": null}""");
    }

    @Test
    void 카드만_끄면_설정은_남는다() {
        makeActive();
        config(InterviewConfigKeys.CARD_ENABLED, "false");

        assertThat(status()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"cardEligible": false, "cardUrl": null, "settingsEnabled": true, "settingsUrl": "%s"}""".formatted(SETTINGS_URL));
    }

    @Test
    void 켜져_있어도_링크가_비어_있으면_보이지_않는다() {
        config(InterviewConfigKeys.SETTINGS_URL, "");

        assertThat(status())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.settingsEnabled")
                .isEqualTo(false);
    }

    @Test
    void 인증이_없으면_401이다() {
        assertThat(mvc.get()
                        .uri("/api/interview/status")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
