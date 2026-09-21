package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import tools.jackson.databind.ObjectMapper;

/** BY-724 세션 제출의 완료 할 일 (ADR-0022) — 저장·응답·소유 검증·자정 귀속·중복·멱등·삭제된 할 일. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionCompletedTaskApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    /** doneAt이 null이면 미완료 할 일 — 앱이 완료 해제한 뒤 제출한 경우를 흉내 낸다. */
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
                doneAt.atOffset(ZoneOffset.UTC));
    }

    private MvcTestResult submit(Instant start, Instant end, String completedTaskIdsJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": [], "completedTaskIds": %s}""".formatted(start, end, completedTaskIdsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private int countRows(Long sessionId) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_task_done WHERE session_id = ?", Integer.class, sessionId);
        return rows == null ? 0 : rows;
    }

    private long firstSessionId(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();
    }

    @Test
    void 완료한_할_일을_함께_제출하면_응답에_실리고_행이_저장되며_상세_조회에도_나온다() {
        Long t1 = insertTask(subjectId, "3단원", sessionStart.plusSeconds(600));
        Long t2 = insertTask(subjectId, "4단원", sessionStart.plusSeconds(1200));

        MvcTestResult created = submit(sessionStart, sessionEnd, "[%d, %d]".formatted(t2, t1));
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$[0].completedTaskIds.length()", v -> assertThat(v).isEqualTo(2))
                // 응답은 id 오름차순 — 보낸 순서와 무관하다
                .hasPathSatisfying(
                        "$[0].completedTaskIds[0]", v -> assertThat(v).isEqualTo(t1.intValue()))
                .hasPathSatisfying(
                        "$[0].completedTaskIds[1]", v -> assertThat(v).isEqualTo(t2.intValue()));

        long sessionId = firstSessionId(created);
        assertThat(countRows(sessionId)).isEqualTo(2);

        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.completedTaskIds.length()", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 자정을_넘는_제출은_완료_시각이_속한_조각에_붙고_완료_시각이_없으면_마지막_조각이다() {
        // 그저께 23:00 ~ 어제 01:00 (KST) — 항상 과거라 미래 검증에 걸리지 않는다
        Instant start = today.minusDays(2).atStartOfDay(KST).plusHours(23).toInstant();
        Instant end = start.plusSeconds(7200);
        Long beforeMidnight = insertTask(subjectId, "23:30 완료", start.plusSeconds(1800));
        Long afterMidnight = insertTask(subjectId, "00:30 완료", start.plusSeconds(5400));
        Long undone = insertTask(subjectId, "해제 뒤 제출", null);

        assertThat(submit(start, end, "[%d, %d, %d]".formatted(beforeMidnight, afterMidnight, undone)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].completedTaskIds.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$[0].completedTaskIds[0]", v -> assertThat(v).isEqualTo(beforeMidnight.intValue()))
                .hasPathSatisfying(
                        "$[1].completedTaskIds.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[1].completedTaskIds[0]", v -> assertThat(v).isEqualTo(afterMidnight.intValue()))
                .hasPathSatisfying(
                        "$[1].completedTaskIds[1]", v -> assertThat(v).isEqualTo(undone.intValue()));
    }

    @Test
    void 다른_사용자의_할_일이면_400이고_세션도_저장되지_않는다() {
        Long mine = insertTask(subjectId, "내 것", sessionStart.plusSeconds(600));
        Long others = insertTask(insertSubject(insertUser(), "남의 과목"), "남의 것", sessionStart.plusSeconds(600));

        assertThat(submit(sessionStart, sessionEnd, "[%d, %d]".formatted(mine, others)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", v -> assertThat(v).isEqualTo("사용자의 할 일이 아닙니다"));

        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
    }

    @Test
    void 완료_할_일_없이_제출하면_빈_배열이고_기존처럼_저장된다() {
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
                        "$[0].completedTaskIds.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 중복_id는_한_번만_저장되고_같은_제출을_다시_보내면_그대로_돌아온다() {
        Long task = insertTask(subjectId, "3단원", sessionStart.plusSeconds(600));

        MvcTestResult first = submit(sessionStart, sessionEnd, "[%d, %d]".formatted(task, task));
        assertThat(first)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].completedTaskIds.length()", v -> assertThat(v).isEqualTo(1));
        long sessionId = firstSessionId(first);
        assertThat(countRows(sessionId)).isEqualTo(1);

        // 멱등 재제출 — 본문의 다른 필드는 무시되고 저장된 결과가 그대로 온다
        MvcTestResult again = submit(sessionStart, sessionEnd, "[]");
        assertThat(again)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].id", v -> assertThat(v).isEqualTo((int) sessionId))
                .hasPathSatisfying(
                        "$[0].completedTaskIds[0]", v -> assertThat(v).isEqualTo(task.intValue()));
        assertThat(countRows(sessionId)).isEqualTo(1);
    }

    @Test
    void 자동확정본을_최종_제출이_대체하면_완료_할_일은_새_세션에_붙고_옛_세션의_행은_함께_사라진다() {
        Long task = insertTask(subjectId, "3단원", sessionStart.plusSeconds(600));
        Long autoFinalizedId = jdbcTemplate.queryForObject(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true) RETURNING id",
                Long.class,
                userId,
                today.minusDays(1),
                sessionStart.atOffset(ZoneOffset.UTC),
                sessionStart.atOffset(ZoneOffset.UTC),
                sessionStart.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                3600,
                3300);
        // 잠정 기록에 붙어 있던(가정) 완료 행 — 대체되면 세션과 함께 사라져야 한다
        jdbcTemplate.update(
                "INSERT INTO study_session_task_done (session_id, task_id) VALUES (?, ?)", autoFinalizedId, task);

        MvcTestResult replaced = submit(sessionStart, sessionEnd, "[%d]".formatted(task));
        assertThat(replaced)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].completedTaskIds[0]", v -> assertThat(v).isEqualTo(task.intValue()));
        long newSessionId = firstSessionId(replaced);

        assertThat(newSessionId).isNotEqualTo(autoFinalizedId);
        assertThat(countRows(autoFinalizedId)).isZero();
        assertThat(countRows(newSessionId)).isEqualTo(1);
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_task_done WHERE task_id = ?", Integer.class, task);
        assertThat(totalRows).isEqualTo(1);
    }

    @Test
    void 세션_중_지운_할_일도_기록된다() {
        Long task = insertTask(subjectId, "지운 할 일", sessionStart.plusSeconds(600));
        jdbcTemplate.update("UPDATE study_task SET deleted_at = now() WHERE id = ?", task);

        assertThat(submit(sessionStart, sessionEnd, "[%d]".formatted(task)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].completedTaskIds[0]", v -> assertThat(v).isEqualTo(task.intValue()));
    }
}
