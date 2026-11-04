package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
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
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/**
 * 공부 리듬(GET /api/stats/rhythm) — 전체 측정 세션의 순공을 요일 × 시각 격자에 누적한다.
 * 누적 공부일은 study-days와 같은 1분 기준이고, 격자 합은 1분 이상 세션의 순공 합과 같아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyRhythmApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final LocalDate yesterday = LocalDate.now(KST).minusDays(1);

    /** 어제의 요일 인덱스(월=0). */
    private final int yesterdayRow = yesterday.getDayOfWeek().getValue() - 1;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KST).toInstant();
    }

    private void submit(Instant startedAt, int studySec, int focusSec, String eventsJson) {
        submit(startedAt, studySec, focusSec, eventsJson, null);
    }

    private void submit(Instant startedAt, int studySec, int focusSec, String eventsJson, String timeZone) {
        String zoneField = timeZone == null ? "" : ", \"timeZone\": \"%s\"".formatted(timeZone);
        String body =
                """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s%s}""".formatted(startedAt, startedAt.plusSeconds(studySec), studySec, focusSec, eventsJson, zoneField);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);
    }

    // 구 앱 대응이 없는 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcTester.MockMvcRequestBuilder rhythm(Long user) {
        return mvc.get()
                .uri("/api/stats/rhythm")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(user));
    }

    private String cell(int row, int hour) {
        return "$.focusSecByWeekdayHour[%d][%d]".formatted(row, hour);
    }

    private static final List<List<Integer>> ZERO_GRID = Collections.nCopies(7, Collections.nCopies(24, 0));

    /** JsonPath는 중첩 배열을 집계하지 못해 격자 합은 여기서 더한다. */
    private static int sum(Object grid) {
        int total = 0;
        for (Object row : (List<?>) grid) {
            for (Object sec : (List<?>) row) {
                total += ((Number) sec).intValue();
            }
        }
        return total;
    }

    @Test
    void 기록이_없으면_공부일_0과_모두_0인_7x24_격자다() {
        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studyDays", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.focusSecByWeekdayHour", v -> assertThat(v).isEqualTo(ZERO_GRID));
    }

    @Test
    void 정시_경계로_잘라_요일과_시각_칸에_배분하고_합은_순공과_같다() {
        // 어제 20:30~22:00, 총 90분 중 순공 60분 → 20시 칸 20분, 21시 칸 40분 (길이 비율)
        submit(at(yesterday, 20, 30), 5400, 3600, "[]");

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studyDays", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(cell(yesterdayRow, 20), v -> assertThat(v).isEqualTo(1200))
                .hasPathSatisfying(cell(yesterdayRow, 21), v -> assertThat(v).isEqualTo(2400))
                .hasPathSatisfying(
                        "$.focusSecByWeekdayHour",
                        v -> assertThat(v).extracting(StudyRhythmApiTest::sum).isEqualTo(3600));
    }

    @Test
    void 이벤트가_있던_시간은_순공_배분에서_빠진다() {
        // 어제 20:00~22:00, 21시 구간이 통째로 PHONE → 순공 60분이 전부 20시 칸에 간다
        Instant start = at(yesterday, 20, 0);
        submit(start, 7200, 3600, """
                [{"status": "PHONE", "startedAt": "%s", "endedAt": "%s"}]""".formatted(at(yesterday, 21, 0), at(yesterday, 22, 0)));

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(cell(yesterdayRow, 20), v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(cell(yesterdayRow, 21), v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 마지막_구간이_이벤트여도_나눗셈_나머지는_마지막_구간이_가져간다() {
        // 20:00~23:00, 순공 7201초, 22시 구간이 통째로 PHONE → 20시·21시 3600초씩, 나머지 1초는 규칙대로 마지막(22시) 칸
        Instant start = at(yesterday, 20, 0);
        submit(start, 10800, 7201, """
                [{"status": "PHONE", "startedAt": "%s", "endedAt": "%s"}]""".formatted(at(yesterday, 22, 0), at(yesterday, 23, 0)));

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(cell(yesterdayRow, 20), v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(cell(yesterdayRow, 21), v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(cell(yesterdayRow, 22), v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.focusSecByWeekdayHour",
                        v -> assertThat(v).extracting(StudyRhythmApiTest::sum).isEqualTo(7201));
    }

    @Test
    void 요일과_시각_칸은_그_세션의_시간대로_정한다() {
        // 뉴욕에서 어제 20:30~22:00 공부했다. 서울 시각으로는 다음 날 오전이지만, 공부한 곳의 시각인 20·21시 칸에 칠한다
        ZoneId newYork = ZoneId.of("America/New_York");
        LocalDate newYorkYesterday = LocalDate.now(newYork).minusDays(1);
        int row = newYorkYesterday.getDayOfWeek().getValue() - 1;
        submit(newYorkYesterday.atTime(20, 30).atZone(newYork).toInstant(), 5400, 3600, "[]", newYork.getId());

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(cell(row, 20), v -> assertThat(v).isEqualTo(1200))
                .hasPathSatisfying(cell(row, 21), v -> assertThat(v).isEqualTo(2400))
                .hasPathSatisfying(
                        "$.focusSecByWeekdayHour",
                        v -> assertThat(v).extracting(StudyRhythmApiTest::sum).isEqualTo(3600));
    }

    @Test
    void 종료_시각이_없는_레거시_세션은_건너뛴다() {
        // 초기 스키마는 ended_at이 null일 수 있다 — 구간을 못 자르니 읽지 않고, 다른 세션은 그대로 집계한다
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec) VALUES (?, ?, ?, ?, NULL, 1200, 1200)",
                userId,
                java.sql.Date.valueOf(yesterday.minusDays(1)),
                java.sql.Timestamp.from(at(yesterday.minusDays(1), 9, 0)),
                java.sql.Timestamp.from(at(yesterday.minusDays(1), 9, 0)));
        submit(at(yesterday, 20, 0), 3600, 3600, "[]");

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studyDays", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(cell(yesterdayRow, 20), v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(
                        "$.focusSecByWeekdayHour",
                        v -> assertThat(v).extracting(StudyRhythmApiTest::sum).isEqualTo(3600));
    }

    @Test
    void 순공_1분_미만_세션은_공부일에도_격자에도_넣지_않는다() {
        submit(at(yesterday, 9, 0), 600, 59, "[]");

        assertThat(rhythm(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studyDays", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.focusSecByWeekdayHour", v -> assertThat(v).isEqualTo(ZERO_GRID));
    }

    @Test
    void 존재하지_않는_userId도_404가_아니라_빈_응답이다() {
        assertThat(rhythm(Long.MAX_VALUE))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studyDays", v -> assertThat(v).isEqualTo(0));
    }
}
