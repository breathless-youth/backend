package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

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
import project.study.TestcontainersConfiguration;

/**
 * 누적 공부일(GET /api/stats/study-days) — 가입 이후 순공시간 1분 이상 세션이 하루라도 있었던 날의 수 (BY-645).
 * 목록 조회와 같은 1분 기준(ADR-0009)이라 "목록에 세션이 보이는 날"과 정확히 일치해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyDaysApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final LocalDate yesterday = LocalDate.now(KST).minusDays(1);

    @BeforeEach
    void createUser() {
        userId = createUser("tester");
    }

    private Long createUser(String prefix) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                prefix + "-" + UUID.randomUUID());
    }

    private static Instant noonOf(LocalDate date) {
        return date.atStartOfDay(KST).plusHours(12).toInstant();
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(Long user, Instant startedAt, int durationSec) {
        return submitRequest(user, startedAt, durationSec, durationSec);
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(
            Long user, Instant startedAt, int studySec, int focusSec) {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(user, startedAt, startedAt.plusSeconds(studySec), studySec, focusSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void submit(Long user, Instant startedAt, int durationSec) {
        assertThat(submitRequest(user, startedAt, durationSec)).hasStatus(HttpStatus.CREATED);
    }

    private MockMvcTester.MockMvcRequestBuilder studyDays(Long user) {
        return mvc.get().uri("/api/stats/study-days").param("userId", user.toString());
    }

    @Test
    void 기록이_없으면_0이다() {
        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 존재하지_않는_userId도_404가_아니라_0이다() {
        assertThat(studyDays(Long.MAX_VALUE))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 세션_수가_아니라_날짜_수를_센다() {
        submit(userId, noonOf(yesterday), 600);
        submit(userId, noonOf(yesterday).plusSeconds(3600), 600);
        submit(userId, noonOf(yesterday.minusDays(2)), 600);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 순공시간_1분_미만_세션만_있는_날은_세지_않는다() {
        submit(userId, noonOf(yesterday.minusDays(1)), 59);
        submit(userId, noonOf(yesterday), 60);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 총_공부시간이_아니라_순공시간으로_판정한다() {
        // 총 10분 공부했지만 순공 59초 — 목록과 같은 focusSec 기준이라 세지 않는다
        assertThat(submitRequest(userId, noonOf(yesterday), 600, 59)).hasStatus(HttpStatus.CREATED);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 같은_날_짧은_세션을_합쳐_1분을_넘겨도_세지_않는다() {
        // 세션 단위 기준이지 하루 합산이 아니다 (ADR-0009)
        submit(userId, noonOf(yesterday), 30);
        submit(userId, noonOf(yesterday).plusSeconds(600), 30);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 자정을_걸친_세션은_분할_저장되어_이틀로_센다() {
        // 그제 23:30 ~ 어제 00:30 — 자정 분할로 stat_date가 둘이 된다 — 조각마다 순공 30분이라 각각 인정된다
        Instant lateNight = yesterday
                .minusDays(1)
                .atStartOfDay(KST)
                .plusHours(23)
                .plusMinutes(30)
                .toInstant();
        submit(userId, lateNight, 3600);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(2));
    }

    @Test
    void 다른_유저의_세션은_포함하지_않는다() {
        Long other = createUser("other");
        submit(other, noonOf(yesterday), 600);
        submit(userId, noonOf(yesterday.minusDays(3)), 600);

        assertThat(studyDays(userId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(1));
    }
}
