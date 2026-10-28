package project.study.push;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 푸시 토큰 등록 — 업서트·사용자 이동·검증. 발송은 PushSenderTest가 맡는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PushTokenApiTest {

    private static final String URI = "/api/push-tokens";

    /** 구 앱 대응이 없는 새 경로라 기본버전(1)이다 — asUser가 붙이는 2를 덮어써야 라우팅된다. */
    private static final String PUSH_API_VERSION = "1";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        token = "fcm-" + UUID.randomUUID();
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult put(Long asUserId, String body) {
        return mvc.put()
                .uri(URI)
                .header(ApiVersionConfig.HEADER, PUSH_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(asUserId))
                .exchange();
    }

    private static String body(String token, String platform, boolean notificationsEnabled) {
        return "{\"token\": \"%s\", \"platform\": \"%s\", \"notificationsEnabled\": %s}"
                .formatted(token, platform, notificationsEnabled);
    }

    private Map<String, Object> row(String token) {
        return jdbcTemplate.queryForMap(
                "SELECT user_id, platform, notifications_enabled, created_at, updated_at FROM push_token WHERE token = ?",
                token);
    }

    @Test
    void 새_토큰을_등록하면_204이고_행이_생긴다() {
        assertThat(put(userId, body(token, "IOS", true))).hasStatus(HttpStatus.NO_CONTENT);

        Map<String, Object> row = row(token);
        assertThat(row.get("user_id")).isEqualTo(userId);
        assertThat(row.get("platform")).isEqualTo("IOS");
        assertThat(row.get("notifications_enabled")).isEqualTo(true);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void 같은_토큰을_다시_등록하면_행은_하나이고_권한_상태와_갱신_시각만_바뀐다() {
        put(userId, body(token, "IOS", true));
        // 같은 값이어도 갱신 시각이 움직이는지 보려고 과거로 돌려 둔다
        jdbcTemplate.update("UPDATE push_token SET updated_at = now() - interval '1 day' WHERE token = ?", token);
        Object createdAt = row(token).get("created_at");

        assertThat(put(userId, body(token, "IOS", false))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM push_token WHERE token = ?", Long.class, token))
                .isEqualTo(1L);
        Map<String, Object> row = row(token);
        assertThat(row.get("notifications_enabled")).isEqualTo(false);
        assertThat(row.get("created_at")).isEqualTo(createdAt);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT updated_at > now() - interval '1 minute' FROM push_token WHERE token = ?",
                        Boolean.class,
                        token))
                .isTrue();
    }

    @Test
    void 다른_사용자에게_붙어_있던_토큰이면_지금_사용자로_옮긴다() {
        Long other = insertUser();
        put(other, body(token, "ANDROID", true));

        assertThat(put(userId, body(token, "ANDROID", true))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(row(token).get("user_id")).isEqualTo(userId);
    }

    @Test
    void IOS_ANDROID_밖의_플랫폼은_400이다() {
        assertThat(put(userId, body(token, "WEB", true))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_비었거나_권한_상태가_없으면_400이다() {
        assertThat(put(userId, body(" ", "IOS", true))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(userId, "{\"token\": \"%s\", \"platform\": \"IOS\"}".formatted(token)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM push_token WHERE user_id = ?", Long.class, userId))
                .isZero();
    }

    @Test
    void 테스트_발송은_설정으로_켜지_않으면_경로가_없다() {
        assertThat(mvc.post()
                        .uri(URI + "/test")
                        .header(ApiVersionConfig.HEADER, PUSH_API_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(asUser(userId))
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 인증_없이_부르면_401이다() {
        assertThat(mvc.put()
                        .uri(URI)
                        .header(ApiVersionConfig.HEADER, PUSH_API_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token, "IOS", true))
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
