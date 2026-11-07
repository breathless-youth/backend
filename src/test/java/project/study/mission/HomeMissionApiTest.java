package project.study.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.mission.service.HomeMissionService;
import project.study.mission.service.HomeMissionUserDataHandler;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HomeMissionApiTest {
    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HomeMissionUserDataHandler dataHandler;

    @Autowired
    HomeMissionService service;

    @Test
    void 기존_과목으로_첫_단계를_확인하고_재시도해도_다음_단계를_건너뛰지_않는다() {
        long user = jdbc.sql(
                        "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', :p, :n) RETURNING id")
                .param("p", UUID.randomUUID().toString())
                .param("n", "mission-" + UUID.randomUUID())
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        assertThat(mvc.get()
                        .uri("/api/home/mission")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.eligible", v -> assertThat(v).isEqualTo(true));
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk();
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.completedSteps", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 다른_날의_순공은_합산하지_않고_같은_날의_측정만_합산한다() {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        insertSession(user, "2026-11-06T14:00:00Z", "2026-11-06T14:00:00Z", 600, "MEASURED");
        insertSession(user, "2026-11-06T15:01:00Z", "2026-11-06T15:01:00Z", 600, "MANUAL");
        insertSession(user, "2026-11-06T15:15:00Z", "2026-11-06T15:15:00Z", 180, "MEASURED");
        insertSession(user, "2026-11-07T15:15:00Z", "2026-11-07T15:15:00Z", 120, "MEASURED");
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.eligible", v -> assertThat(v).isEqualTo(false));
        insertSession(user, "2026-11-07T15:30:00Z", "2026-11-07T15:30:00Z", 180, "MEASURED");
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/2/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.completedSteps", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 시작일_자정을_넘긴_측정은_시작일의_분할_조각을_인정한다() {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        insertSession(user, "2026-11-06T15:00:00Z", "2026-11-06T14:55:00Z", 300, "MEASURED");
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.eligible", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 계정_병합은_더_앞선_단계를_보존하고_탈퇴시_지운다() {
        long source = insertUser();
        long target = insertUser();
        jdbc.sql("INSERT INTO home_mission_progress VALUES (:u, 'home-v3', 2)")
                .param("u", source)
                .update();
        jdbc.sql("INSERT INTO home_mission_progress VALUES (:u, 'home-v3', 1)")
                .param("u", target)
                .update();
        dataHandler.merge(source, target);
        assertThat(jdbc.sql("SELECT completed_steps FROM home_mission_progress WHERE user_id=:u")
                        .param("u", target)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        dataHandler.erase(target);
        assertThat(jdbc.sql("SELECT count(*) FROM home_mission_progress WHERE user_id IN (:a,:b)")
                        .param("a", source)
                        .param("b", target)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void 완료_전에_마지막_과목을_삭제하면_조건이_사라진다() {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name, deleted_at) VALUES (:u, '삭제', now())")
                .param("u", user)
                .update();
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void 동시에_같은_단계를_확인해도_한_단계만_진행한다() throws Exception {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        insertSession(user, "2026-11-06T15:15:00Z", "2026-11-06T15:15:00Z", 300, "MEASURED");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return service.complete(user, 1);
            });
            var second = executor.submit(() -> {
                start.await();
                return service.complete(user, 1);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).completedSteps()).isEqualTo(1);
            assertThat(second.get(10, TimeUnit.SECONDS).completedSteps()).isEqualTo(1);
            assertThat(service.get(user).currentStep()).isEqualTo(2);
        }
    }

    @Test
    void 토큰이_없으면_조회와_완료를_거부한다() {
        assertThat(mvc.get()
                        .uri("/api/home/mission")
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/1/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 첫_단계_확인_전에_두번째_단계를_완료할_수_없다() {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        insertSession(user, "2026-11-06T15:15:00Z", "2026-11-06T15:15:00Z", 300, "MEASURED");
        assertThat(mvc.post()
                        .uri("/api/home/mission/steps/2/complete")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(service.get(user).completedSteps()).isZero();
    }

    @Test
    void 완료한_단계는_과목이나_기록을_지워도_유지된다() {
        long user = insertUser();
        jdbc.sql("INSERT INTO study_subject (user_id, name) VALUES (:u, '수학')")
                .param("u", user)
                .update();
        insertSession(user, "2026-11-06T15:15:00Z", "2026-11-06T15:15:00Z", 300, "MEASURED");
        service.complete(user, 1);
        service.complete(user, 2);
        jdbc.sql("UPDATE study_subject SET deleted_at=now() WHERE user_id=:u")
                .param("u", user)
                .update();
        jdbc.sql("DELETE FROM study_session WHERE user_id=:u").param("u", user).update();
        assertThat(service.get(user).completedSteps()).isEqualTo(2);
    }

    private long insertUser() {
        return jdbc.sql("INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', :p, :n) RETURNING id")
                .param("p", UUID.randomUUID().toString())
                .param("n", "mission-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private void insertSession(long user, String started, String root, int focus, String source) {
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source)
                VALUES (:u, (CAST(:started AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date, CAST(:started AS timestamptz), CAST(:root AS timestamptz), CAST(:started AS timestamptz) + interval '10 minutes', :focus, :focus, :source)
                """)
                .param("u", user)
                .param("started", started)
                .param("root", root)
                .param("focus", focus)
                .param("source", source)
                .update();
    }
}
