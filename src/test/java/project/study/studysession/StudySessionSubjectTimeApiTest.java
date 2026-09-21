package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import project.study.studysession.buffer.ActiveSnapshotBuffer;

/** BY-698 세션 계약 확장 — 제출·스냅샷·복구의 subjectTimes 저장·검증·자정 분할·누적 합산. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionSubjectTimeApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    private Long userId;
    private Long subjectId;

    private final LocalDate today = LocalDate.now(KST);
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = insertUser();
        subjectId = insertSubject(userId, "수학");
    }

    // 스케줄러 풀스캔 오염 방지 — ActiveSessionSnapshotApiTest와 같은 이유
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Long insertSubject(Long ownerId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, ownerId, name);
    }

    private static String time(Long subject, int studySec, int focusSec) {
        return """
                {"subjectId": %d, "studySec": %d, "focusSec": %d}""".formatted(subject, studySec, focusSec);
    }

    private MvcTestResult submit(Instant start, Instant end, int studySec, int focusSec, String subjectTimesJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": [], "subjectTimes": %s}""".formatted(start, end, studySec, focusSec, subjectTimesJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    @Test
    void 세션_제출의_과목별_시간이_저장되고_과목_목록_누적에_합산된다() {
        String times = "[" + time(subjectId, 4000, 3500) + "," + time(subjectId, 2000, 1800) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, times))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].subjectTimes.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjectTimes[0].subjectId", v -> assertThat(v).isEqualTo(subjectId.intValue()));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_subject_time WHERE subject_id = ?", Integer.class, subjectId);
        assertThat(rows).isEqualTo(2);

        assertThat(mvc.get().uri("/api/subjects").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(6000))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(5300));
    }

    @Test
    void 과목별_시간_없이_제출하면_기존처럼_저장된다() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": []}""".formatted(sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].subjectTimes.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 과목_시간_합이_세션_총공부를_넘으면_400이고_세션도_저장되지_않는다() {
        String times = "[" + time(subjectId, 5000, 4000) + "," + time(subjectId, 3000, 2000) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, times)).hasStatus(HttpStatus.BAD_REQUEST);

        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
    }

    @Test
    void 다른_사용자의_과목이면_400이다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, "[" + time(otherSubjectId, 100, 90) + "]"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 자정을_넘는_제출은_과목_시간도_두_조각으로_나뉜다() {
        // 그저께 23:00 ~ 어제 01:00 (KST) — 항상 과거라 미래 검증에 걸리지 않는다
        Instant start = today.minusDays(2).atStartOfDay(KST).plusHours(23).toInstant();
        Instant end = start.plusSeconds(7200);

        assertThat(submit(start, end, 7200, 6000, "[" + time(subjectId, 6000, 5000) + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjectTimes[0].studySec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying(
                        "$[0].subjectTimes[0].focusSec", v -> assertThat(v).isEqualTo(2500))
                .hasPathSatisfying(
                        "$[1].subjectTimes[0].studySec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying(
                        "$[1].subjectTimes[0].focusSec", v -> assertThat(v).isEqualTo(2500));
    }

    @Test
    void 스냅샷의_항목별_시간은_복구_조회에_그대로_돌아온다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant reported = Instant.now().minusSeconds(60);
        String body = """
                {"startedAt": "%s", "reportedAt": "%s", "studySec": 7000, "focusSec": 6500, "events": [], "subjectTimes": [%s]}""".formatted(started, reported, time(subjectId, 3000, 2800));

        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush();

        assertThat(mvc.get().uri("/api/study-sessions/active").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.subjectTimes.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.subjectTimes[0].subjectId", v -> assertThat(v).isEqualTo(subjectId.intValue()))
                .hasPathSatisfying(
                        "$.subjectTimes[0].studySec", v -> assertThat(v).isEqualTo(3000));
    }
}
