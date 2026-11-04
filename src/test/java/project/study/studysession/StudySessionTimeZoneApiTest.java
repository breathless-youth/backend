package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 세션 제출·수동 기록의 timeZone (ADR-0041) — 현지 자정 분할, 사용자 시간대 저장, 잘못된 값은 저장된 시간대로. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionTimeZoneApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    // 실제 시계를 쓰므로 미래 판정에 걸리지 않게 사흘 전을 쓴다
    private final LocalDate day = LocalDate.now(NEW_YORK).minusDays(3);

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tz-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private Instant at(ZoneId zone, LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(zone).toInstant();
    }

    private MockMvcRequestBuilder submit(Instant start, Instant end, String timeZoneJson) {
        int length = (int) (end.getEpochSecond() - start.getEpochSecond());
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []%s}""".formatted(start, end, length, length, timeZoneJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId));
    }

    private List<LocalDate> statDates() {
        return jdbc.queryForList(
                "SELECT stat_date FROM study_session WHERE user_id = ? ORDER BY started_at", LocalDate.class, userId);
    }

    private List<String> sessionZones() {
        return jdbc.queryForList(
                "SELECT DISTINCT time_zone FROM study_session WHERE user_id = ?", String.class, userId);
    }

    private String userZone() {
        return jdbc.queryForObject("SELECT time_zone FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void 뉴욕_시간대로_제출하면_뉴욕_자정에서_나누고_사용자_시간대도_저장한다() {
        assertThat(submit(
                        at(NEW_YORK, day, 23),
                        at(NEW_YORK, day.plusDays(1), 1),
                        ", \"timeZone\": \"America/New_York\""))
                .hasStatus(HttpStatus.CREATED);
        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("America/New_York");
        assertThat(userZone()).isEqualTo("America/New_York");
    }

    @Test
    void 시간대가_없는_옛_요청은_예전처럼_서울_자정으로_나눈다() {
        assertThat(submit(at(SEOUL, day, 23), at(SEOUL, day.plusDays(1), 1), ""))
                .hasStatus(HttpStatus.CREATED);
        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("Asia/Seoul");
        assertThat(userZone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void 잘못된_시간대는_거절하지_않고_저장된_사용자_시간대로_저장한다() {
        jdbc.update("UPDATE users SET time_zone = 'America/New_York' WHERE id = ?", userId);

        assertThat(submit(at(NEW_YORK, day, 23), at(NEW_YORK, day.plusDays(1), 1), ", \"timeZone\": \"Mars/Olympus\""))
                .hasStatus(HttpStatus.CREATED);
        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("America/New_York");
        assertThat(userZone()).isEqualTo("America/New_York");
    }

    @Test
    void 수동_기록도_보낸_시간대의_자정에서_나누고_사용자_시간대를_저장한다() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "timeZone": "America/New_York"}""".formatted(at(NEW_YORK, day, 23), at(NEW_YORK, day.plusDays(1), 1));

        assertThat(mvc.post()
                        .uri("/api/study-sessions/manual")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("America/New_York");
        assertThat(userZone()).isEqualTo("America/New_York");
    }

    private List<Long> sessionIds() {
        return jdbc.queryForList(
                "SELECT id FROM study_session WHERE user_id = ? ORDER BY started_at", Long.class, userId);
    }

    @Test
    void 같은_제출을_시간대와_함께_다시_보내면_사용자_시간대만_저장하고_세션은_그대로다() {
        Instant start = at(SEOUL, day, 23);
        Instant end = at(SEOUL, day.plusDays(1), 1);
        assertThat(submit(start, end, "")).hasStatus(HttpStatus.CREATED);
        List<Long> ids = sessionIds();

        assertThat(submit(start, end, ", \"timeZone\": \"America/New_York\"")).hasStatus(HttpStatus.CREATED);

        assertThat(userZone()).isEqualTo("America/New_York");
        assertThat(sessionIds()).isEqualTo(ids);
        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("Asia/Seoul");
    }

    @Test
    void 수동_기록을_고칠_때도_보낸_시간대의_자정에서_나누고_사용자_시간대를_저장한다() {
        assertThat(mvc.post()
                        .uri("/api/study-sessions/manual")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startedAt": "%s", "endedAt": "%s"}""".formatted(at(SEOUL, day, 9), at(SEOUL, day, 10)))
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);
        long id = sessionIds().getFirst();
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "timeZone": "America/New_York"}""".formatted(at(NEW_YORK, day, 23), at(NEW_YORK, day.plusDays(1), 1));

        assertThat(mvc.patch()
                        .uri("/api/study-sessions/manual/{id}", id)
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatusOk();

        assertThat(statDates()).containsExactly(day, day.plusDays(1));
        assertThat(sessionZones()).containsExactly("America/New_York");
        assertThat(userZone()).isEqualTo("America/New_York");
    }
}
