package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 일간 조회의 subjects(ADR-0026) — 살아있는 과목 전부와 그날과 관련 있는 지운 과목, 과목마다 그날의 할 일.
 * 과목 순서, 할 일의 생성·완료·삭제 시각 경계(KST 자정), 소유, 그리고 오늘 날짜 응답이 과목 목록 API와 같은지.
 * 고정 날짜는 10월 4일(KST)이고 그날은 UTC로 10-03T15:00 이상 10-04T15:00 미만이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyStatsDaySubjectsApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DAY = "2026-10-04";

    /** 그날보다 한참 전 — 생성 시각이 관심사가 아닌 할 일에 쓴다. */
    private static final String LONG_BEFORE = "2026-09-01T00:00:00Z";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = insertUser();
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

    /** created_at은 기본값이 now()라, 지난 날의 할 일을 만들려면 직접 넣어야 한다. doneAt이 null이면 미완료다. */
    private Long insertTask(Long subjectId, String name, String createdAt, String doneAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO study_task (subject_id, name, created_at, updated_at, done_at)
                VALUES (?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
                RETURNING id""", Long.class, subjectId, name, createdAt, createdAt, doneAt);
    }

    private void deleteTaskAt(Long taskId, String deletedAt) {
        jdbcTemplate.update(
                "UPDATE study_task SET deleted_at = CAST(? AS timestamptz) WHERE id = ?", deletedAt, taskId);
    }

    /** 과목 API는 기본버전(1)에 매핑돼 있다 — asUser가 붙이는 2를 덮어써야 라우팅된다. */
    private MvcTestResult sendSubjectApi(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(ApiVersionConfig.HEADER, "1").with(asUser(userId)).exchange();
    }

    /** 이름만 받는 생성 API(과목·할 일)를 불러 만들어진 id를 돌려준다. */
    private long createViaApi(String uri, String name) {
        return bodyOf(sendSubjectApi(mvc.post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}")))
                .get("id")
                .asLong();
    }

    private void deleteSubjectViaApi(Long subjectId) {
        assertThat(sendSubjectApi(mvc.delete().uri("/api/subjects/" + subjectId)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private MvcTestResult stats(Long asUserId, String date) {
        return mvc.get()
                .uri("/api/stats")
                .param("date", date)
                .with(asUser(asUserId))
                .exchange();
    }

    private MvcTestResult stats(String date) {
        return stats(userId, date);
    }

    private JsonNode bodyOf(MvcTestResult result) {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<Long> idsOf(JsonNode array) {
        return array.valueStream().map(node -> node.get("id").asLong()).toList();
    }

    private List<Long> subjectIdsOf(MvcTestResult result) {
        return idsOf(bodyOf(result).get("subjects"));
    }

    /** 응답의 subjects에서 그 과목의 tasks 배열. 과목이 없으면 실패한다. */
    private JsonNode tasksOf(MvcTestResult result, Long subjectId) {
        for (JsonNode subject : bodyOf(result).get("subjects")) {
            if (subject.get("id").asLong() == subjectId) {
                return subject.get("tasks");
            }
        }
        throw new AssertionError("subjects에 과목 " + subjectId + "이 없다");
    }

    @Test
    void 공부도_할_일도_없는_살아있는_과목이_빈_tasks로_실린다() {
        Long subjectId = insertSubject(userId, "수학");

        assertThat(stats(DAY))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.subjects[0].id", v -> assertThat(v).isEqualTo(subjectId.intValue()))
                .hasPathSatisfying("$.subjects[0].name", v -> assertThat(v).isEqualTo("수학"))
                .hasPathSatisfying(
                        "$.subjects[0].colorIndex", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.subjects[0].tasks", v -> assertThat(v).isEqualTo(List.of()));
    }

    @Test
    void 살아있는_과목은_사용자가_정한_순서로_실린다() {
        long first = createViaApi("/api/subjects", "국어");
        long second = createViaApi("/api/subjects", "영어");
        long third = createViaApi("/api/subjects", "수학");
        assertThat(subjectIdsOf(stats(DAY))).containsExactly(first, second, third);

        MvcTestResult reordered = sendSubjectApi(mvc.put()
                .uri("/api/subjects/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectIds\": [%d, %d, %d]}".formatted(third, first, second)));
        assertThat(reordered).hasStatusOk();

        assertThat(subjectIdsOf(stats(DAY)))
                .containsExactly(third, first, second)
                .isEqualTo(idsOf(bodyOf(reordered)));
    }

    @Test
    void 지운_과목은_그날과_관련_있을_때만_살아있는_과목_뒤에_id_순으로_실린다() {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        Instant sessionStart = yesterday.atStartOfDay(KST).plusHours(12).toInstant();
        Instant sessionEnd = sessionStart.plusSeconds(7200);
        // 지운 과목을 먼저 만들어 id가 작다 — 그래도 살아있는 과목 뒤에 온다
        Long goneWithSession = insertSubject(userId, "세션이 참조한 지운 과목");
        Long goneWithTask = insertSubject(userId, "그날의 할 일이 있는 지운 과목");
        Long goneUnrelated = insertSubject(userId, "그날과 무관한 지운 과목");
        Long live = insertSubject(userId, "살아있는 과목");
        Long doneThatDay = insertTask(
                goneWithTask,
                "그날 완료한 할 일",
                LONG_BEFORE,
                sessionStart.plusSeconds(1800).toString());
        insertTask(goneUnrelated, "한참 전에 완료한 할 일", LONG_BEFORE, "2026-09-02T00:00:00Z");
        // 과목을 지우면 할 일도 지금 시각으로 함께 지워진다 — 그날보다 뒤다
        deleteSubjectViaApi(goneWithSession);
        deleteSubjectViaApi(goneWithTask);
        deleteSubjectViaApi(goneUnrelated);

        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 7200, "events": [],
                 "subjectSegments": [{"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}]}""".formatted(sessionStart, sessionEnd, goneWithSession, sessionStart, sessionEnd);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult result = stats(yesterday.toString());
        assertThat(subjectIdsOf(result)).containsExactly(live, goneWithSession, goneWithTask);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.subjects[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.subjects[1].deleted", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.subjects[1].tasks", v -> assertThat(v).isEqualTo(List.of()))
                .hasPathSatisfying("$.subjects[2].deleted", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.subjects[2].tasks[0].id", v -> assertThat(v).isEqualTo(doneThatDay.intValue()))
                .hasPathSatisfying(
                        "$.subjects[2].tasks[0].done", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.subjects[2].tasks[0].deleted", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 세션_없이_완료한_할_일과_미완료_할_일이_과목_아래에_실린다() {
        Long subjectId = insertSubject(userId, "수학");
        // KST 10월 4일 21시 30분에 완료 — 세션도, 세션 기록(study_session_task_done)도 없다
        Long doneId = insertTask(subjectId, "3단원 문제풀기", LONG_BEFORE, "2026-10-04T12:30:00Z");
        Long openId = insertTask(subjectId, "4단원 개념 정리", LONG_BEFORE, null);

        MvcTestResult result = stats(DAY);
        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.subjects[0].tasks.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[0].id", v -> assertThat(v).isEqualTo(doneId.intValue()))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[0].name", v -> assertThat(v).isEqualTo("3단원 문제풀기"))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[0].done", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[0].doneAt", v -> assertThat(v).isEqualTo("2026-10-04T12:30:00Z"))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[1].id", v -> assertThat(v).isEqualTo(openId.intValue()))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[1].done", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying(
                        "$.subjects[0].tasks[1].deleted", v -> assertThat(v).isEqualTo(false));
        // 미완료의 doneAt은 키가 빠지지 않고 null로 내려간다
        JsonNode open = tasksOf(result, subjectId).get(1);
        assertThat(open.has("doneAt")).isTrue();
        assertThat(open.get("doneAt").isNull()).isTrue();
    }

    @Test
    void 그날이_끝난_뒤에_만든_할_일은_실리지_않는다() {
        Long subjectId = insertSubject(userId, "영어");
        Long lastSecond = insertTask(subjectId, "그날 23:59:59에 만든 것", "2026-10-04T14:59:59Z", null);
        insertTask(subjectId, "다음 날 00:00에 만든 것", "2026-10-04T15:00:00Z", null);
        insertTask(subjectId, "다음 날 만들어 바로 완료한 것", "2026-10-05T01:00:00Z", "2026-10-05T02:00:00Z");

        assertThat(idsOf(tasksOf(stats(DAY), subjectId))).containsExactly(lastSecond);
    }

    @Test
    void 그날_시작_전에_완료한_할_일은_빠지고_그날_뒤에_완료한_것은_뒤_날짜_완료_시각으로_실린다() {
        Long subjectId = insertSubject(userId, "영어");
        insertTask(subjectId, "전날 23:59:59 완료", LONG_BEFORE, "2026-10-03T14:59:59Z");
        Long doneLater = insertTask(subjectId, "이틀 뒤 완료", LONG_BEFORE, "2026-10-06T01:00:00Z");
        Long lastSecond = insertTask(subjectId, "그날 23:59:59 완료", LONG_BEFORE, "2026-10-04T14:59:59Z");
        Long firstSecond = insertTask(subjectId, "그날 00:00 완료", LONG_BEFORE, "2026-10-03T15:00:00Z");

        // 완료 시각과 무관하게 id 오름차순이다 — 과목 목록 API와 같다
        JsonNode tasks = tasksOf(stats(DAY), subjectId);
        assertThat(idsOf(tasks)).containsExactly(doneLater, lastSecond, firstSecond);
        // 그날 기준으로는 미완료였지만 지금 값 그대로 내려간다 — 그날 완료했는지는 앱이 doneAt으로 고른다
        assertThat(tasks.get(0).get("done").asBoolean()).isTrue();
        assertThat(tasks.get(0).get("doneAt").asString()).isEqualTo("2026-10-06T01:00:00Z");
    }

    @Test
    void 그날_시작_전에_지운_할_일은_빠지고_그날_이후에_지운_것은_표시와_함께_실린다() {
        Long subjectId = insertSubject(userId, "국어");
        Long deletedBefore = insertTask(subjectId, "전날 23:59:59에 지운 것", LONG_BEFORE, null);
        deleteTaskAt(deletedBefore, "2026-10-03T14:59:59Z");
        Long deletedThatDay = insertTask(subjectId, "그날 00:00에 지운 것", LONG_BEFORE, null);
        deleteTaskAt(deletedThatDay, "2026-10-03T15:00:00Z");
        Long deletedLater = insertTask(subjectId, "다음 날 지운 것", LONG_BEFORE, null);
        deleteTaskAt(deletedLater, "2026-10-05T03:00:00Z");
        Long alive = insertTask(subjectId, "안 지운 것", LONG_BEFORE, null);

        JsonNode tasks = tasksOf(stats(DAY), subjectId);
        assertThat(idsOf(tasks)).containsExactly(deletedThatDay, deletedLater, alive);
        assertThat(tasks.get(0).get("deleted").asBoolean()).isTrue();
        assertThat(tasks.get(1).get("deleted").asBoolean()).isTrue();
        assertThat(tasks.get(2).get("deleted").asBoolean()).isFalse();
    }

    @Test
    void 다른_사용자의_과목과_할_일은_실리지_않고_과목이_없으면_빈_배열이다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");
        insertTask(otherSubjectId, "남의 완료한 할 일", LONG_BEFORE, "2026-10-04T12:30:00Z");
        insertTask(otherSubjectId, "남의 미완료 할 일", LONG_BEFORE, null);

        assertThat(stats(DAY))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.subjects", v -> assertThat(v).isEqualTo(List.of()));
        // 없는 유저도 같은 빈 응답이다
        assertThat(stats(Long.MAX_VALUE, DAY))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects", v -> assertThat(v).isEqualTo(List.of()));
    }

    @Test
    void 구_앱_경로는_토큰_없이_열려_있어_과목과_할_일을_싣지_않는다() {
        Long subjectId = insertSubject(userId, "수학");
        insertTask(subjectId, "3단원 문제풀기", LONG_BEFORE, "2026-10-04T12:30:00Z");
        insertTask(subjectId, "4단원 개념 정리", LONG_BEFORE, null);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", String.valueOf(userId))
                        .param("date", DAY))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.subjects", v -> assertThat(v).isEqualTo(List.of()));
    }

    @Test
    void 오늘_날짜의_살아있는_과목과_지우지_않은_할_일은_과목_목록_API와_같다() {
        long math = createViaApi("/api/subjects", "수학");
        long english = createViaApi("/api/subjects", "영어");
        long emptySubject = createViaApi("/api/subjects", "할 일 없는 과목");
        long gone = createViaApi("/api/subjects", "지운 과목");
        String mathTasks = "/api/subjects/" + math + "/tasks";
        long open = createViaApi(mathTasks, "미완료");
        long doneToday = createViaApi(mathTasks, "오늘 완료");
        assertThat(patchTask(mathTasks + "/" + doneToday, "{\"done\": true}")).hasStatusOk();
        long undone = createViaApi(mathTasks, "완료했다가 해제");
        assertThat(patchTask(mathTasks + "/" + undone, "{\"done\": true}")).hasStatusOk();
        assertThat(patchTask(mathTasks + "/" + undone, "{\"done\": false}")).hasStatusOk();
        long deletedToday = createViaApi(mathTasks, "오늘 지움");
        assertThat(sendSubjectApi(mvc.delete().uri(mathTasks + "/" + deletedToday)))
                .hasStatus(HttpStatus.NO_CONTENT);
        // 이틀 전에 완료 — 과목 목록에서도, 오늘의 할 일에서도 빠진다
        insertTask(
                english,
                "이틀 전 완료",
                LONG_BEFORE,
                Instant.now().minusSeconds(2 * 86400).toString());
        long englishOpen = createViaApi("/api/subjects/" + english + "/tasks", "영어 미완료");
        createViaApi("/api/subjects/" + gone + "/tasks", "지운 과목의 할 일");
        deleteSubjectViaApi(gone);

        JsonNode listed = bodyOf(sendSubjectApi(mvc.get().uri("/api/subjects")));
        MvcTestResult result = stats(LocalDate.now(KST).toString());

        // 일간 조회에서 살아있는 과목과 지우지 않은 할 일만 남기면 과목 목록 API와 같다 — 순서, 할 일 id, 완료 시각까지
        assertThat(liveLines(bodyOf(result).get("subjects"))).isEqualTo(liveLines(listed));
        // 비교가 비어 있지 않은지 — 과목 셋, 수학의 할 일 셋(오늘 지운 것 제외), 영어 하나
        assertThat(idsOf(listed)).containsExactly(math, english, emptySubject);
        assertThat(idsOf(listed.get(0).get("tasks"))).containsExactly(open, doneToday, undone);
        assertThat(idsOf(listed.get(1).get("tasks"))).containsExactly(englishOpen);
        // 과목 목록 API에 없는 것 — 오늘 지운 할 일과 오늘 지운 과목은 일간 조회에만 표시와 함께 실린다
        assertThat(idsOf(tasksOf(result, math))).containsExactly(open, doneToday, undone, deletedToday);
        assertThat(subjectIdsOf(result)).containsExactly(math, english, emptySubject, gone);
    }

    private MvcTestResult patchTask(String taskUri, String body) {
        return sendSubjectApi(
                mvc.patch().uri(taskUri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 살아있는 과목과 지우지 않은 할 일만 한 줄씩 편다 — 과목 목록 API 응답에는 deleted가 없어 없으면 false로 본다. */
    private static List<String> liveLines(JsonNode subjects) {
        List<String> lines = new ArrayList<>();
        for (JsonNode subject : subjects) {
            if (subject.path("deleted").asBoolean(false)) {
                continue;
            }
            lines.add(subject.get("id") + ":" + subject.get("name") + ":" + subject.get("colorIndex"));
            for (JsonNode task : subject.get("tasks")) {
                if (!task.path("deleted").asBoolean(false)) {
                    lines.add("  " + task.get("id") + ":" + task.get("name") + ":" + task.get("doneAt"));
                }
            }
        }
        return lines;
    }
}
