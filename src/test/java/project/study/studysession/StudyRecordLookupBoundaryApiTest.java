package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

/**
 * BY-734 기록 조회 확장의 경계 — 구 앱(v1, 익명 userId) 경로에는 과목·할 일 이름을 싣지 않고(Codex 리뷰 P1),
 * 일간 목록은 세션 수와 무관하게 자식 컬렉션을 배치로 읽는다(P2). 본 계약은 StudyRecordLookupApiTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyRecordLookupBoundaryApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

    private static String segment(Long subject, Instant start, Instant end) {
        return """
                {"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}""".formatted(subject, start, end);
    }

    /** 12:00~13:00 영어, 13:00~13:30 수학(지운 과목), 완료 할 일 둘 다 — 이름이 붙을 재료가 전부 있는 세션. */
    private MvcTestResult submitFullSession() {
        return submitWith(
                sessionStart,
                sessionEnd,
                "[" + segment(english, sessionStart, sessionStart.plusSeconds(3600)) + ","
                        + segment(deletedMath, sessionStart.plusSeconds(3600), sessionStart.plusSeconds(5400)) + "]",
                "[" + taskDoneYesterday + ", " + deletedTask + "]");
    }

    private MvcTestResult submitWith(Instant start, Instant end, String segmentsJson, String completedTaskIdsJson) {
        int sec = (int) Duration.between(start, end).toSeconds();
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": [],
                 "subjectSegments": %s, "completedTaskIds": %s}""".formatted(start, end, sec, sec, segmentsJson, completedTaskIdsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    @Test
    void 구_앱_경로에는_과목_할_일_이름을_싣지_않는다() {
        MvcTestResult created = submitFullSession();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        long sessionId = firstSessionId(created);

        // 토큰 없이 쿼리 userId로 열리는 v1 경로 — 구간(id)·이벤트는 그대로, 이름이 붙는 필드만 빈 배열
        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", String.valueOf(userId))
                        .param("date", yesterday.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.sessions[0].subjectSegments.length()",
                        v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.sessions[0].completedTasks.length()",
                        v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(0));

        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).param("userId", String.valueOf(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.completedTasks.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.subjects.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 일간_목록은_세션_수와_무관하게_자식_컬렉션을_배치로_읽는다() {
        for (int hour : new int[] {8, 11, 14}) {
            Instant start = yesterday.atStartOfDay(KST).plusHours(hour).toInstant();
            assertThat(submitWith(
                            start,
                            start.plusSeconds(3600),
                            "[" + segment(english, start, start.plusSeconds(3600)) + "]",
                            "[" + taskDoneYesterday + "]"))
                    .hasStatus(HttpStatus.CREATED);
        }
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("date", yesterday.toString())
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(3));

        // 세션 1 + 공부일 1 + 자식 컬렉션 배치 3(이벤트·구간·할 일) + 이름 조회 2(할 일·과목) = 7. @BatchSize가 없으면 세션마다 3번씩 늘어난다
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8);
        statistics.setStatisticsEnabled(false);
    }
}
