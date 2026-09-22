package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import tools.jackson.databind.ObjectMapper;

/**
 * BY-734 기록 탭 일간 조회 확장 — 일간 목록·상세·제출 응답에 세션별 이벤트·과목 구간·완료 할 일(이름)과
 * 참조 과목의 이름·색이 실리고, 지운 과목·할 일과 어제 완료한 할 일의 이름도 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyRecordLookupApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long english;
    private Long deletedMath;
    private Long taskDoneYesterday;
    private Long deletedTask;

    private final LocalDate yesterday = LocalDate.now(KST).minusDays(1);
    private final Instant sessionStart =
            yesterday.atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = insertUser();
        english = insertSubject(userId, "영어", 2);
        deletedMath = insertSubject(userId, "수학", 5);
        jdbcTemplate.update("UPDATE study_subject SET deleted_at = now() WHERE id = ?", deletedMath);
        // 어제 13:00 완료 — 과목 목록 API의 "오늘 완료"에는 안 보인다
        taskDoneYesterday = insertTask(english, "문제집 1장 풀기", sessionStart.plusSeconds(3600));
        deletedTask = insertTask(deletedMath, "오답노트 정리", null);
        jdbcTemplate.update("UPDATE study_task SET deleted_at = now() WHERE id = ?", deletedTask);
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Long insertSubject(Long ownerId, String name, int colorIndex) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_subject (user_id, name, color_index) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                ownerId,
                name,
                colorIndex);
    }

    private Long insertTask(Long subject, String name, Instant doneAt) {
        if (doneAt == null) {
            return jdbcTemplate.queryForObject(
                    "INSERT INTO study_task (subject_id, name) VALUES (?, ?) RETURNING id", Long.class, subject, name);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_task (subject_id, name, done_at) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                subject,
                name,
                java.sql.Timestamp.from(doneAt));
    }

    private long firstSessionId(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();
    }

    private MvcTestResult submitWith(Instant start, Instant end, String segmentsJson, String completedTaskIdsJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": [],
                 "subjectSegments": %s, "completedTaskIds": %s}""".formatted(
                        start,
                        end,
                        (int) java.time.Duration.between(start, end).toSeconds(),
                        (int) java.time.Duration.between(start, end).toSeconds(),
                        segmentsJson,
                        completedTaskIdsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private static String segment(Long subject, Instant start, Instant end) {
        return """
                {"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}""".formatted(subject, start, end);
    }

    /** 12:00~13:00 영어, 13:00~13:30 수학(지운 과목), PHONE 12:10~12:20, 완료 할 일 둘 다. */
    private MvcTestResult submitFullSession() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600,
                 "events": [{"status": "PHONE", "startedAt": "%s", "endedAt": "%s"}],
                 "subjectSegments": [%s, %s],
                 "completedTaskIds": [%d, %d]}""".formatted(
                        sessionStart,
                        sessionEnd,
                        sessionStart.plusSeconds(600),
                        sessionStart.plusSeconds(1200),
                        segment(english, sessionStart, sessionStart.plusSeconds(3600)),
                        segment(deletedMath, sessionStart.plusSeconds(3600), sessionStart.plusSeconds(5400)),
                        taskDoneYesterday,
                        deletedTask);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    @Test
    void 일간_목록_한_번에_세션별_이벤트_구간_완료_할_일과_과목_이름_색이_실린다() {
        assertThat(submitFullSession()).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("date", yesterday.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.sessions[0].events.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.sessions[0].events[0].status", v -> assertThat(v).isEqualTo("PHONE"))
                .hasPathSatisfying(
                        "$.sessions[0].subjectSegments.length()",
                        v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.sessions[0].subjectSegments[0].focusSec",
                        v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks.length()",
                        v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[0].id",
                        v -> assertThat(v).isEqualTo(taskDoneYesterday.intValue()))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[0].name",
                        v -> assertThat(v).isEqualTo("문제집 1장 풀기"))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[0].subjectId",
                        v -> assertThat(v).isEqualTo(english.intValue()))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[0].deleted",
                        v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[1].name",
                        v -> assertThat(v).isEqualTo("오답노트 정리"))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks[1].deleted",
                        v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.subjects[0].id", v -> assertThat(v).isEqualTo(english.intValue()))
                .hasPathSatisfying("$.subjects[0].name", v -> assertThat(v).isEqualTo("영어"))
                .hasPathSatisfying(
                        "$.subjects[0].colorIndex", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.subjects[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.subjects[1].id", v -> assertThat(v).isEqualTo(deletedMath.intValue()))
                .hasPathSatisfying("$.subjects[1].name", v -> assertThat(v).isEqualTo("수학"))
                .hasPathSatisfying("$.subjects[1].deleted", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 어제_완료한_할_일은_과목_목록엔_없지만_기록_응답엔_이름이_실린다() {
        assertThat(mvc.get()
                        .uri("/api/subjects")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].tasks.length()", v -> assertThat(v).isEqualTo(0));

        MvcTestResult created = submitFullSession();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        long sessionId = firstSessionId(created);

        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.completedTasks[0].name", v -> assertThat(v).isEqualTo("문제집 1장 풀기"))
                .hasPathSatisfying(
                        "$.completedTasks[0].deleted", v -> assertThat(v).isEqualTo(false));
    }

    @Test
    void 제출_응답과_세션_상세도_일간_목록과_같은_모양이다() {
        MvcTestResult created = submitFullSession();
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].completedTasks.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$[0].subjects.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjects[1].deleted", v -> assertThat(v).isEqualTo(true));
        long sessionId = firstSessionId(created);

        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.subjectSegments.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.completedTasks.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.subjects[0].colorIndex", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 완료_할_일의_과목은_구간에_없어도_subjects에_실린다() {
        // 구간 없이 완료 할 일만 — 할 일의 과목(영어)이 subjects에 붙는다
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": [], "completedTaskIds": [%d]}""".formatted(sessionStart, sessionEnd, taskDoneYesterday);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].subjectSegments.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$[0].subjects.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].subjects[0].name", v -> assertThat(v).isEqualTo("영어"));
    }

    @Test
    void 과목도_할_일도_없는_세션은_빈_배열이다() {
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
                        "$[0].completedTasks.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$[0].subjects.length()", v -> assertThat(v).isEqualTo(0));

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("date", yesterday.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.sessions[0].events.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 여러_세션의_과목은_중복_없이_합쳐지고_자정_분할_제출_응답의_subjects는_조각별이다() {
        // 어제 08:00~09:00 영어, 15:00~16:00 영어(다시) + 지운 할 일(수학)
        Instant morning = yesterday.atStartOfDay(KST).plusHours(8).toInstant();
        Instant afternoon = yesterday.atStartOfDay(KST).plusHours(15).toInstant();
        assertThat(submitWith(
                        morning,
                        morning.plusSeconds(3600),
                        "[" + segment(english, morning, morning.plusSeconds(3600)) + "]",
                        "[]"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(submitWith(
                        afternoon,
                        afternoon.plusSeconds(3600),
                        "[" + segment(english, afternoon, afternoon.plusSeconds(3600)) + "]",
                        "[" + deletedTask + "]"))
                .hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("date", yesterday.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.subjects[0].name", v -> assertThat(v).isEqualTo("영어"))
                .hasPathSatisfying("$.subjects[1].name", v -> assertThat(v).isEqualTo("수학"));

        // 그저께 23:00 ~ 어제 01:00 — 첫 조각에만 영어 구간, 완료 시각 없는 지운 할 일(수학)은 마지막 조각
        Instant crossStart =
                yesterday.minusDays(1).atStartOfDay(KST).plusHours(23).toInstant();
        assertThat(submitWith(
                        crossStart,
                        crossStart.plusSeconds(7200),
                        "[" + segment(english, crossStart, crossStart.plusSeconds(1800)) + "]",
                        "[" + deletedTask + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$[0].subjects.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].subjects[0].name", v -> assertThat(v).isEqualTo("영어"))
                .hasPathSatisfying(
                        "$[0].completedTasks.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$[1].subjects.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[1].subjects[0].name", v -> assertThat(v).isEqualTo("수학"))
                .hasPathSatisfying(
                        "$[1].completedTasks[0].subjectId", v -> assertThat(v).isEqualTo(deletedMath.intValue()));
    }
}
