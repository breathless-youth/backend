package project.study.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import project.study.subject.dto.SubjectResponse;
import project.study.subject.service.StudySubjectService;
import tools.jackson.databind.ObjectMapper;

/** BY-698 과목 > 할 일 API — 생성·완료 노출 규칙·soft delete·소유·상한, BY-724 순서 저장·색 배정. 누적 합산은 StudySessionSubjectTimeApiTest. */
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

    @Autowired
    private StudySubjectService subjectService;

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

    private MvcTestResult putJson(String uri, String body) {
        return mvc.put()
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

    private List<Long> idsOf(MvcTestResult result) {
        List<Long> ids = new ArrayList<>();
        objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private MvcTestResult deleteSubject(long subjectId) {
        return mvc.delete()
                .uri("/api/subjects/" + subjectId)
                .header(ApiVersionConfig.HEADER, SUBJECT_API_VERSION)
                .with(asUser(userId))
                .exchange();
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

    @Test
    void 순서를_저장하면_목록이_그_순서로_내려오고_새_과목은_맨_뒤에_붙는다() {
        long a = idOf(postJson("/api/subjects", "{\"name\": \"국어\"}"));
        long b = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        long c = idOf(postJson("/api/subjects", "{\"name\": \"영어\"}"));

        MvcTestResult reordered = putJson("/api/subjects/order", "{\"subjectIds\": [%d, %d, %d]}".formatted(c, a, b));
        assertThat(reordered).hasStatusOk();
        assertThat(idsOf(reordered)).containsExactly(c, a, b);
        assertThat(idsOf(list())).containsExactly(c, a, b);

        long d = idOf(postJson("/api/subjects", "{\"name\": \"탐구\"}"));
        assertThat(idsOf(list())).containsExactly(c, a, b, d);
    }

    @Test
    void 순서에서_빠진_과목은_기존_순서대로_뒤에_붙는다() {
        long a = idOf(postJson("/api/subjects", "{\"name\": \"국어\"}"));
        long b = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        long c = idOf(postJson("/api/subjects", "{\"name\": \"영어\"}"));

        assertThat(putJson("/api/subjects/order", "{\"subjectIds\": [%d]}".formatted(c)))
                .hasStatusOk();

        assertThat(idsOf(list())).containsExactly(c, a, b);
    }

    @Test
    void 순서에_남의_과목이나_중복이_섞이면_400이고_순서는_바뀌지_않는다() {
        long a = idOf(postJson("/api/subjects", "{\"name\": \"국어\"}"));
        long b = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        Long other = insertSubject(insertUser(), "남의 과목");

        assertThat(putJson("/api/subjects/order", "{\"subjectIds\": [%d, %d]}".formatted(other, b)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(putJson("/api/subjects/order", "{\"subjectIds\": [%d, %d]}".formatted(b, b)))
                .hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(idsOf(list())).containsExactly(a, b);
    }

    @Test
    void 같은_사용자가_동시에_만들어도_색과_순서가_겹치지_않는다() throws Exception {
        // 사용자 락이 없으면 두 트랜잭션이 같은 빈 목록을 읽어 둘 다 색 0·순서 0을 받는다
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<SubjectResponse>> results = new ArrayList<>();
            for (String name : List.of("국어", "수학")) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return subjectService.create(userId, name);
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> colors = new ArrayList<>();
            for (Future<SubjectResponse> result : results) {
                colors.add(result.get().colorIndex());
            }
            assertThat(colors).containsExactlyInAnyOrder(0, 1);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> sortOrders = jdbcTemplate.queryForList(
                "SELECT sort_order FROM study_subject WHERE user_id = ? ORDER BY sort_order", Integer.class, userId);
        assertThat(sortOrders).containsExactly(0, 1);
    }

    @Test
    void 과목_색은_덜_쓴_인덱스를_받고_지운_과목의_색은_다시_쓰인다() {
        MvcTestResult first = postJson("/api/subjects", "{\"name\": \"국어\"}");
        long b = idOf(postJson("/api/subjects", "{\"name\": \"수학\"}"));
        MvcTestResult third = postJson("/api/subjects", "{\"name\": \"영어\"}");
        assertThat(first)
                .bodyJson()
                .hasPathSatisfying("$.colorIndex", v -> assertThat(v).isEqualTo(0));
        assertThat(third)
                .bodyJson()
                .hasPathSatisfying("$.colorIndex", v -> assertThat(v).isEqualTo(2));

        assertThat(deleteSubject(b)).hasStatus(HttpStatus.NO_CONTENT);

        // 색 1이 풀렸으니 다음 과목은 1 — 목록 색도 그대로 내려온다
        assertThat(postJson("/api/subjects", "{\"name\": \"탐구\"}"))
                .bodyJson()
                .hasPathSatisfying("$.colorIndex", v -> assertThat(v).isEqualTo(1));
        assertThat(list())
                .bodyJson()
                .hasPathSatisfying("$[2].colorIndex", v -> assertThat(v).isEqualTo(1));
    }
}
