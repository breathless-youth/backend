package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 수동 기록이 개인 집계에는 들어가고 측정 전용 집계에는 빠지는지 (ADR-0031). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualStudySessionStatsApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    // 실제 시계를 쓰므로 미래 판정에 걸리지 않게 이틀 전을 쓴다
    private final LocalDate day = LocalDate.now(KST).minusDays(2);

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KST).toInstant();
    }

    private Instant at(int hour, int minute) {
        return at(day, hour, minute);
    }

    // 새 경로라 기본버전 1이다. asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder post(Instant start, Instant end, Long subjectId) {
        return mvc.post()
                .uri("/api/study-sessions/manual")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startedAt": "%s", "endedAt": "%s", "subjectId": %s}""".formatted(start, end, subjectId))
                .with(asUser(userId));
    }

    private MockMvcRequestBuilder post(Instant start, Instant end) {
        return post(start, end, null);
    }

    /** 측정 세션 제출. 총공부는 길이 전체, 순공은 focusSec. */
    private void measured(Instant start, Instant end, int focusSec) {
        int length = (int) (end.getEpochSecond() - start.getEpochSecond());
        String request = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(start, end, length, focusSec);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 일간_조회는_합계에_수동을_넣고_집중률과_최대_집중은_측정만_본다() {
        measured(at(10, 0), at(11, 0), 1800);
        assertThat(post(at(13, 0), at(16, 0))).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get().uri("/api/stats").param("date", day.toString()).with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(3600 + 10800))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(1800 + 10800))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(50.0))
                .hasPathSatisfying("$.longestFocusSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.sessions[0].source", v -> assertThat(v).isEqualTo("MANUAL"))
                .hasPathSatisfying("$.sessions[1].source", v -> assertThat(v).isEqualTo("MEASURED"))
                .hasPathSatisfying(
                        "$.studiedDatesInMonth", v -> assertThat(v).asArray().contains(day.toString()));
    }

    @Test
    void 과목_누적과_기간_통계에는_수동_기록이_들어간다() {
        long math = jdbc.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, userId, "수학");
        measured(at(10, 0), at(11, 0), 1800);
        assertThat(post(at(13, 0), at(15, 0), math)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/subjects")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(7200))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(7200));
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.dailyList[0].studySec", v -> assertThat(v).isEqualTo(3600 + 7200))
                .hasPathSatisfying("$.dailyList[0].focusSec", v -> assertThat(v).isEqualTo(1800 + 7200));
    }

    @Test
    void 수동_기록만_있는_날은_연속_공부와_누적_공부일에_세지_않는다() {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        assertThat(post(at(yesterday, 10, 0), at(yesterday, 12, 0))).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats/streak")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.maxStreak", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.studiedDatesInRange.length()", v -> assertThat(v).isEqualTo(0));
        assertThat(mvc.get()
                        .uri("/api/stats/study-days")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 세션_복구는_뒤에_넣은_수동_기록이_아니라_최근_측정_세션을_본다() {
        Instant started = at(9, 0);
        jdbc.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, 3600, 3400, true)",
                userId,
                java.sql.Date.valueOf(day),
                Timestamp.from(started),
                Timestamp.from(started),
                Timestamp.from(started.plusSeconds(3600)));
        assertThat(post(at(14, 0), at(15, 0))).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.post().uri("/api/study-sessions/recovery").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studySec", v -> assertThat(v).isEqualTo(3600));
    }
}
