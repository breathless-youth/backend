package project.study.planner.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 기본값을 복사하거나 0을 미설정으로 취급하면 상속·해제 검증이 실패한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlannerGoalApiTest {

    private static final String URI = "/api/planner/goals";
    private static final String FUTURE = "/2030-03-02";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock plannerGoalClock() {
            // 서울 3월 2일 04:59: 플래너의 오늘은 3월 1일이다.
            return Clock.fixed(Instant.parse("2030-03-01T19:59:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = insertUser();
    }

    @Test
    void 미설정과_명시적인_0을_구분한다() {
        assertGoal(get(userId, FUTURE), null, null, null);

        assertThat(put(userId, "/default", "{\"goalSec\":3600}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goalSec", value -> assertThat(value).isEqualTo(3600));
        assertGoal(get(userId, FUTURE), 3600, null, 3600);

        assertGoal(put(userId, FUTURE, "{\"goalSec\":0}"), 0, 0, 3600);
        assertGoal(get(userId, FUTURE), 0, 0, 3600);
    }

    @Test
    void 개별_해제는_기본값을_다시_상속하고_기본값_해제는_미설정으로_돌린다() {
        put(userId, "/default", "{\"goalSec\":3600}");
        put(userId, FUTURE, "{\"goalSec\":1800}");
        put(userId, "/default", "{\"goalSec\":7200}");
        assertGoal(get(userId, FUTURE), 1800, 1800, 7200);

        assertThat(delete(userId, FUTURE)).hasStatus(HttpStatus.NO_CONTENT);
        assertGoal(get(userId, FUTURE), 7200, null, 7200);

        assertThat(delete(userId, "/default")).hasStatus(HttpStatus.NO_CONTENT);
        assertGoal(get(userId, FUTURE), null, null, null);
        assertThat(delete(userId, FUTURE)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(userId, "/default")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 기본값을_지워도_개별_0은_보존된다() {
        put(userId, "/default", "{\"goalSec\":3600}");
        put(userId, FUTURE, "{\"goalSec\":0}");

        delete(userId, "/default");

        assertGoal(get(userId, FUTURE), 0, 0, null);
        assertThat(get(userId, "/default"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goalSec", value -> assertThat(value).isNull());
    }

    @Test
    void 최대_86400초와_0을_저장하고_upsert는_중복_행을_만들지_않는다() {
        assertGoal(put(userId, FUTURE, "{\"goalSec\":86400}"), 86400, 86400, null);
        assertGoal(put(userId, FUTURE, "{\"goalSec\":0}"), 0, 0, null);
        assertThat(count("planner_daily_goal", userId)).isEqualTo(1);
        assertThat(put(userId, "/default", "{\"goalSec\":86400}")).hasStatusOk();
        assertThat(put(userId, "/default", "{\"goalSec\":0}")).hasStatusOk();
        assertThat(count("planner_default_goal", userId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"{\"goalSec\":-1800}", "{\"goalSec\":88200}", "{\"goalSec\":1}", "{\"goalSec\":null}", "{}"})
    void 범위_단위_필수값을_검증하고_기존값을_보존한다(String body) {
        put(userId, "/default", "{\"goalSec\":3600}");
        put(userId, FUTURE, "{\"goalSec\":1800}");

        assertThat(put(userId, "/default", body)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(userId, FUTURE, body)).hasStatus(HttpStatus.BAD_REQUEST);

        assertGoal(get(userId, FUTURE), 1800, 1800, 3600);
    }

    @Test
    void 오늘과_과거의_개별_설정과_삭제는_거절하고_조회는_허용한다() {
        for (String date : new String[] {"/2030-03-01", "/2030-02-28"}) {
            assertThat(put(userId, date, "{\"goalSec\":1800}")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(delete(userId, date)).hasStatus(HttpStatus.BAD_REQUEST);
            assertGoal(get(userId, date), null, null, null);
        }
        assertThat(count("planner_daily_goal", userId)).isZero();
    }

    @Test
    void 쓰기의_현지_05시_날짜로_미래를_판정하고_조회는_시간대를_바꾸지_않는다() {
        assertThat(put(userId, FUTURE + "?timeZone=Pacific/Auckland", "{\"goalSec\":1800}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        // 실패한 쓰기와 함께 시간대 변경도 롤백된다.
        assertThat(jdbc.sql("SELECT time_zone FROM users WHERE id = :u")
                        .param("u", userId)
                        .query(String.class)
                        .single())
                .isEqualTo("Asia/Seoul");
        assertThat(get(userId, FUTURE + "?timeZone=Pacific/Auckland")).hasStatusOk();
        assertThat(jdbc.sql("SELECT time_zone FROM users WHERE id = :u")
                        .param("u", userId)
                        .query(String.class)
                        .single())
                .isEqualTo("Asia/Seoul");
        assertThat(put(userId, FUTURE, "{\"goalSec\":1800}")).hasStatusOk();
        assertThat(put(userId, FUTURE + "?timeZone=Asia/Seoul", "{\"goalSec\":1800}"))
                .hasStatusOk();
    }

    @Test
    void 다른_사용자의_목표는_조회_변경_삭제할_수_없다() {
        put(userId, "/default", "{\"goalSec\":3600}");
        put(userId, FUTURE, "{\"goalSec\":1800}");
        Long other = insertUser();
        assertGoal(get(other, FUTURE), null, null, null);
        put(other, "/default", "{\"goalSec\":7200}");
        put(other, FUTURE, "{\"goalSec\":0}");
        delete(other, FUTURE);
        delete(other, "/default");

        assertGoal(get(userId, FUTURE), 1800, 1800, 3600);
    }

    @Test
    void 토큰_없는_요청은_인증이_필요하다() {
        assertThat(mvc.get().uri(URI + FUTURE).header(ApiVersionConfig.HEADER, "1"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.put()
                        .uri(URI + "/default")
                        .header(ApiVersionConfig.HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalSec\":1800}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private Long insertUser() {
        return jdbc.sql("INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', :p, :n) RETURNING id")
                .param("p", UUID.randomUUID().toString())
                .param("n", "goal-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private MvcTestResult put(Long asUserId, String suffix, String body) {
        return mvc.put()
                .uri(URI + suffix)
                .header(ApiVersionConfig.HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(asUserId))
                .exchange();
    }

    private MvcTestResult get(Long asUserId, String suffix) {
        return mvc.get()
                .uri(URI + suffix)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(asUserId))
                .exchange();
    }

    private MvcTestResult delete(Long asUserId, String suffix) {
        return mvc.delete()
                .uri(URI + suffix)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(asUserId))
                .exchange();
    }

    private long count(String table, Long id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", id)
                .query(Long.class)
                .single();
    }

    private void assertGoal(MvcTestResult result, Integer effective, Integer individual, Integer defaultGoal) {
        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goalSec", value -> assertThat(value).isEqualTo(effective))
                .hasPathSatisfying(
                        "$.individualGoalSec", value -> assertThat(value).isEqualTo(individual))
                .hasPathSatisfying(
                        "$.defaultGoalSec", value -> assertThat(value).isEqualTo(defaultGoal));
    }
}
