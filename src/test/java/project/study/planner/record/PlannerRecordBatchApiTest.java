package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlannerRecordBatchApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long user;

    @BeforeEach
    void setup() {
        user = newUser();
    }

    @Test
    void measuredEditKeepsMeasurementEventsAndRankingSlots() {
        long id = measured("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        jdbc.update(
                "INSERT INTO status_event (session_id,status,started_at,ended_at) VALUES (?,'PHONE',?,?)",
                id,
                ts("2026-10-01T10:20:00Z"),
                ts("2026-10-01T10:30:00Z"));
        jdbc.update(
                "INSERT INTO study_session_slot (session_id,slot_date,slot,focus_sec) VALUES (?,DATE '2026-10-01','MORNING',3000)",
                id);
        assertThat(batch(operation(
                        "edit", "UPDATE", "c:2026-10-01T10:00:00Z", "2026-10-01T10:25:00Z", "2026-10-01T11:15:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
        assertThat(jdbc.queryForObject("SELECT focus_sec FROM study_session WHERE id=?", Integer.class, id))
                .isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT started_at FROM study_session WHERE id=?", Timestamp.class, id))
                .isEqualTo(ts("2026-10-01T10:00:00Z"));
        assertThat(jdbc.queryForObject(
                        "SELECT focus_sec FROM study_session_slot WHERE session_id=?", Integer.class, id))
                .isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_event WHERE session_id=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void batchCommitsValidItemEvenWhenAnotherItemFails() {
        String valid = operation("ok", "CREATE", null, "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z");
        String invalid = operation("bad", "CREATE", null, "2026-10-01T14:00:00Z", "2026-10-01T13:00:00Z");
        assertThat(batch(valid + "," + invalid))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.results[1].status", v -> assertThat(v).isEqualTo(400));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isEqualTo(1);
    }

    @Test
    void retriesReturnOriginalResultAndDifferentPayloadConflicts() {
        String operation = operation("retry", "CREATE", null, "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z");
        assertThat(batch(operation))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
        assertThat(batch(operation))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
        assertThat(batch(operation.replace("13:00", "14:00")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(409));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isEqualTo(1);
    }

    @Test
    void createRejectsAnyOverlapRatherThanSilentlyTrimming() {
        measured("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        assertThat(batch(operation("overlap", "CREATE", null, "2026-10-01T10:30:00Z", "2026-10-01T12:00:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM study_session WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void manualMidnightBundleUpdatesAndDeletesWholeBundle() {
        assertThat(batch(operation("create", "CREATE", null, "2026-10-01T14:30:00Z", "2026-10-01T16:00:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].key", v -> assertThat(v).isEqualTo("m:2026-10-01T14:30:00Z"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isEqualTo(1);
        assertThat(batch(operation(
                        "update", "UPDATE", "m:2026-10-01T14:30:00Z", "2026-10-01T14:00:00Z", "2026-10-01T16:00:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].key", v -> assertThat(v).isEqualTo("m:2026-10-01T14:00:00Z"));
        assertThat(batch(operation("delete", "DELETE", "m:2026-10-01T14:00:00Z", null, null)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isZero();
    }

    @Test
    void cannotMutateAnotherUsersBundle() {
        measured("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        user = newUser();
        assertThat(batch(operation("steal", "DELETE", "c:2026-10-01T10:00:00Z", null, null)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(404));
    }

    @Test
    void futureAndOverTwentyFourHoursAreRejectedPerItem() {
        assertThat(batch(operation("future", "CREATE", null, "2099-10-01T10:00:00Z", "2099-10-01T11:00:00Z") + ","
                        + operation("long", "CREATE", null, "2026-10-01T10:00:00Z", "2026-10-02T10:01:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(400))
                .hasPathSatisfying("$.results[1].status", v -> assertThat(v).isEqualTo(400));
    }

    @Test
    void orderedBatchCanFillTimeFreedByCameraDeletion() {
        measured("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        assertThat(batch(operation("delete", "DELETE", "c:2026-10-01T10:00:00Z", null, null) + ","
                        + operation("manual", "CREATE", null, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.results[1].success", v -> assertThat(v).isEqualTo(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM study_session WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isEqualTo(1);
    }

    @Test
    void failedOperationReplayKeepsOriginalFailureAfterRecordAppears() {
        String operation = operation("missing", "DELETE", "c:2026-10-01T10:00:00Z", null, null);
        assertThat(batch(operation))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(404));
        measured("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        assertThat(batch(operation))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].status", v -> assertThat(v).isEqualTo(404));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, user))
                .isZero();
    }

    @Test
    void simultaneousRetryCreatesExactlyOnePersonalRecord() throws Exception {
        String operation = operation("concurrent", "CREATE", null, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> after(start, operation));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> after(start, operation));
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_operation WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    private void after(CountDownLatch start, String operation) {
        try {
            start.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        assertThat(batch(operation))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void authenticationRequired() {
        assertThat(mvc.post()
                        .uri("/api/planner/records/batch")
                        .header("API-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[]}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MockMvcRequestBuilder batch(String operations) {
        return mvc.post()
                .uri("/api/planner/records/batch")
                .header("API-Version", "1")
                .with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operations\":[" + operations + "]}");
    }

    private static String operation(String id, String action, String key, String from, String to) {
        return """
                {"operationId":"%s","action":"%s","key":%s,"startedAt":%s,"endedAt":%s,"timeZone":"Asia/Seoul"}
                """.formatted(id, action, json(key), json(from), json(to));
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static Timestamp ts(String value) {
        return Timestamp.from(Instant.parse(value));
    }

    private long newUser() {
        return jdbc.queryForObject(
                "INSERT INTO users(provider,provider_user_id,nickname) VALUES ('test',?,?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    private long measured(String from, String to) {
        return jdbc.queryForObject("""
                INSERT INTO study_session(user_id,stat_date,started_at,submission_started_at,ended_at,study_sec,focus_sec)
                VALUES (?,DATE '2026-10-01',?,?,?,3600,3000) RETURNING id
                """, Long.class, user, ts(from), ts(from), ts(to));
    }
}
