package project.study.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import tools.jackson.databind.ObjectMapper;

/** 기간 안에 완료한 할 일 조회 (ADR-0026) — 세션 없는 완료·KST 경계·완료 해제·지운 것 포함·소유·기간 검증. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CompletedTasksApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 과목 API는 기본버전(1)에 매핑돼 있다 — asUser가 붙이는 2를 덮어써야 라우팅된다. */
    private static final String SUBJECT_API_VERSION = "1";

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

    private Long insertDoneTask(Long subjectId, String name, String doneAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_task (subject_id, name, done_at) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                subjectId,
                name,
                Instant.parse(doneAt).atOffset(ZoneOffset.UTC));
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                .with(asUser(userId))
                .exchange();
    }

    private MvcTestResult postJson(String uri, String body) {
        return send(mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcTestResult patchJson(String uri, String body) {
        return send(mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcTestResult completedTasks(String query) {
        return send(mvc.get().uri("/api/subjects/completed-tasks" + query));
    }

    private long idOf(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("id")
                .asLong();
    }

    private List<Long> idsOfTasks(MvcTestResult result) {
        List<Long> ids = new ArrayList<>();
        objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("tasks")
                .forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    @Test
    void 세션_없이_완료한_할_일도_완료_시각과_과목_정보와_함께_내려온다() {
        long subjectId = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        // KST 10월 4일 21시 30분 — 세션 기록(study_session_task_done)은 없다
        Long taskId = insertDoneTask(subjectId, "3단원 문제풀기", "2026-10-04T12:30:00Z");

        assertThat(completedTasks("?from=2026-10-04&to=2026-10-04"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tasks.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.tasks[0].id", v -> assertThat(v).isEqualTo(taskId.intValue()))
                .hasPathSatisfying("$.tasks[0].name", v -> assertThat(v).isEqualTo("3단원 문제풀기"))
                .hasPathSatisfying("$.tasks[0].subjectId", v -> assertThat(v).isEqualTo((int) subjectId))
                .hasPathSatisfying("$.tasks[0].doneAt", v -> assertThat(v).isEqualTo("2026-10-04T12:30:00Z"))
                .hasPathSatisfying("$.tasks[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.subjects[0].name", v -> assertThat(v).isEqualTo("수학"))
                .hasPathSatisfying("$.subjects[0].deleted", v -> assertThat(v).isEqualTo(false));
    }

    @Test
    void 완료_할_일_조회_기간은_KST_자정_기준이고_양끝_날짜를_포함한다() {
        long subjectId = idOf(postJson("/api/subjects", "{\"name\": \"영어\"}"));
        insertDoneTask(subjectId, "전날 23:59:59", "2026-10-03T14:59:59Z");
        Long last = insertDoneTask(subjectId, "끝 날 23:59:59", "2026-10-05T14:59:59Z");
        Long first = insertDoneTask(subjectId, "첫날 00:00", "2026-10-03T15:00:00Z");
        insertDoneTask(subjectId, "다음 날 00:00", "2026-10-05T15:00:00Z");
        insertDoneTask(subjectId, "미완료", "2026-10-04T03:00:00Z");
        jdbcTemplate.update("UPDATE study_task SET done_at = NULL WHERE name = '미완료' AND subject_id = ?", subjectId);

        // 완료 시각 오름차순 — 넣은 순서(id)와 다르다
        assertThat(idsOfTasks(completedTasks("?from=2026-10-04&to=2026-10-05"))).containsExactly(first, last);
    }

    @Test
    void 완료를_해제한_할_일은_완료_할_일_조회에서_빠진다() {
        long subjectId = idOf(postJson("/api/subjects", "{\"name\": \"국어\"}"));
        long taskId = idOf(postJson("/api/subjects/" + subjectId + "/tasks", "{\"name\": \"비문학\"}"));
        String taskUri = "/api/subjects/" + subjectId + "/tasks/" + taskId;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String around = "?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);

        assertThat(patchJson(taskUri, "{\"done\": true}")).hasStatusOk();
        assertThat(idsOfTasks(completedTasks(around))).containsExactly(taskId);

        assertThat(patchJson(taskUri, "{\"done\": false}")).hasStatusOk();
        assertThat(idsOfTasks(completedTasks(around))).isEmpty();
    }

    @Test
    void 지운_할_일과_지운_과목도_완료_할_일_조회에_표시와_함께_실린다() {
        long keptSubject = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        long goneSubject = idOf(postJson("/api/subjects", "{\"name\": \"탐구\"}"));
        Long deletedAlone = insertDoneTask(keptSubject, "지운 할 일", "2026-10-04T01:00:00Z");
        Long deletedWithSubject = insertDoneTask(goneSubject, "과목과 함께 지워진 할 일", "2026-10-04T02:00:00Z");

        assertThat(send(mvc.delete().uri("/api/subjects/" + keptSubject + "/tasks/" + deletedAlone)))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send(mvc.delete().uri("/api/subjects/" + goneSubject))).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult result = completedTasks("?from=2026-10-04&to=2026-10-04");
        assertThat(idsOfTasks(result)).containsExactly(deletedAlone, deletedWithSubject);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.tasks[0].deleted", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.tasks[1].deleted", v -> assertThat(v).isEqualTo(true))
                // subjects는 id 오름차순 — 살아있는 수학, 지운 탐구
                .hasPathSatisfying("$.subjects[0].deleted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.subjects[1].name", v -> assertThat(v).isEqualTo("탐구"))
                .hasPathSatisfying("$.subjects[1].deleted", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 다른_사용자가_완료한_할_일은_조회되지_않는다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");
        insertDoneTask(otherSubjectId, "남의 할 일", "2026-10-04T12:30:00Z");

        assertThat(completedTasks("?from=2026-10-04&to=2026-10-04"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tasks.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 완료_할_일_조회_기간이_거꾸로거나_31일을_넘거나_빠지면_400이다() {
        assertThat(completedTasks("?from=2026-10-05&to=2026-10-04")).hasStatus(HttpStatus.BAD_REQUEST);
        // 10월 1일~31일은 31일이라 된다. 11월 1일까지는 32일
        assertThat(completedTasks("?from=2026-10-01&to=2026-10-31")).hasStatusOk();
        assertThat(completedTasks("?from=2026-10-01&to=2026-11-01")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(completedTasks("?from=2026-10-04")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(completedTasks("?from=10-04&to=2026-10-04")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
