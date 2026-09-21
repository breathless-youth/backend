package project.study.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
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
import project.study.config.ApiVersionConfig;
import tools.jackson.databind.ObjectMapper;

/** BY-698 과목 > 할 일 API — 생성·완료 노출 규칙·soft delete·소유·상한. 누적 합산은 StudySessionSubjectTimeApiTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySubjectApiTest {

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

    private MvcTestResult postJson(String uri, String body) {
        return mvc.post()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private MvcTestResult patchJson(String uri, String body) {
        return mvc.patch()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private MvcTestResult list() {
        return mvc.get()
                .uri("/api/subjects")
                .header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                .with(asUser(userId))
                .exchange();
    }

    private long idOf(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("id")
                .asLong();
    }

    @Test
    void 과목을_만들고_할_일을_붙이면_목록에_누적_0으로_보인다() {
        MvcTestResult created = postJson("/api/subjects", "{\"name\": \" 수학 \"}");
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.name", v -> assertThat(v).isEqualTo("수학"))
                .hasPathSatisfying("$.studySec", v -> assertThat(v).isEqualTo(0));
        long subjectId = idOf(created);

        MvcTestResult task = postJson("/api/subjects/" + subjectId + "/tasks", "{\"name\": \"3단원 문제풀기\"}");
        assertThat(task)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.doneAt")
                .isNull();

        assertThat(list())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].tasks.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].tasks[0].name", v -> assertThat(v).isEqualTo("3단원 문제풀기"))
                .hasPathSatisfying("$[0].tasks[0].name", v -> assertThat(v).isEqualTo("3단원 문제풀기"));
    }

    @Test
    void 완료한_할_일은_그날까지만_보이고_다음_날부터_숨는다() {
        long subjectId = idOf(postJson("/api/subjects", "{\"name\": \"영어\"}"));
        long taskId = idOf(postJson("/api/subjects/" + subjectId + "/tasks", "{\"name\": \"단어 50개\"}"));
        String taskUri = "/api/subjects/" + subjectId + "/tasks/" + taskId;

        assertThat(patchJson(taskUri, "{\"done\": true}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.doneAt", v -> assertThat(v).isNotNull());
        assertThat(list())
                .bodyJson()
                .hasPathSatisfying("$[0].tasks.length()", v -> assertThat(v).isEqualTo(1));

        // 이틀 전에 완료한 것으로 되감으면 오늘 목록에서 빠진다 — 삭제는 아니라서 완료 해제는 여전히 된다
        jdbcTemplate.update(
                "UPDATE study_task SET done_at = ? WHERE id = ?",
                Instant.now().minusSeconds(2 * 86400).atOffset(ZoneOffset.UTC),
                taskId);
        assertThat(list())
                .bodyJson()
                .hasPathSatisfying("$[0].tasks.length()", v -> assertThat(v).isEqualTo(0));

        assertThat(patchJson(taskUri, "{\"done\": false}")).hasStatusOk();
        assertThat(list())
                .bodyJson()
                .hasPathSatisfying("$[0].tasks.length()", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 과목을_지우면_목록에서_사라지고_할_일도_함께_숨는다() {
        long subjectId = idOf(postJson("/api/subjects", "{\"name\": \"국어\"}"));
        long taskId = idOf(postJson("/api/subjects/" + subjectId + "/tasks", "{\"name\": \"비문학\"}"));

        assertThat(mvc.delete()
                        .uri("/api/subjects/" + subjectId)
                        .header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(list())
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(0));
        Boolean taskHidden = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM study_task WHERE id = ?", Boolean.class, taskId);
        assertThat(taskHidden).isTrue();
    }

    @Test
    void 다른_사용자의_과목은_찾을_수_없다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");

        assertThat(patchJson("/api/subjects/" + otherSubjectId, "{\"name\": \"바꾸기\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(postJson("/api/subjects/" + otherSubjectId + "/tasks", "{\"name\": \"끼워넣기\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 살아있는_과목이_20개면_추가가_400이다() {
        for (int i = 0; i < 20; i++) {
            insertSubject(userId, "과목" + i);
        }

        assertThat(postJson("/api/subjects", "{\"name\": \"21번째\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이름이_공백이면_400이다() {
        assertThat(postJson("/api/subjects", "{\"name\": \"   \"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
