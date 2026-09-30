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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

/** 재전송(강제종료 후 복구 등) 멱등 처리 — 멱등 키는 (userId, startedAt). StudySessionApiTest에서 분리(파일 400줄 제한). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionIdempotencyApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final LocalDate today = LocalDate.now(KST);

    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    private final Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();

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

    private MvcTestResult submit(Long uid, Instant startedAt, Instant endedAt, int studySec, int focusSec) {
        String body = """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(uid, startedAt, endedAt, studySec, focusSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private long firstSessionId(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();
    }

    private Integer sessionRows(Long uid) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void 같은_시각으로_재제출하면_기존_세션을_그대로_반환하고_중복_저장하지_않는다() {
        MvcTestResult first = submit(userId, sessionStart, sessionEnd, 7200, 6600);
        assertThat(first).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = submit(userId, sessionStart, sessionEnd, 3600, 3000);
        assertThat(second)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(1))
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(7200));
        assertThat(firstSessionId(second)).isEqualTo(firstSessionId(first));

        assertThat(sessionRows(userId)).isEqualTo(1);
    }

    @Test
    void 자정을_넘는_세션을_재제출하면_두_조각을_그대로_반환한다() {
        MvcTestResult first = submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000);
        assertThat(first).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000);
        assertThat(second)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(2));
        assertThat(firstSessionId(second)).isEqualTo(firstSessionId(first));

        assertThat(sessionRows(userId)).isEqualTo(2);
    }

    @Test
    void 종료_시각이_달라진_재제출도_저장된_조각_전체를_반환한다() {
        MvcTestResult first = submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000);
        assertThat(first).hasStatus(HttpStatus.CREATED);

        MvcTestResult replay = submit(userId, midnight.minusSeconds(3600), midnight.minusSeconds(1800), 1800, 1500);
        assertThat(replay)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> assertThat(length).isEqualTo(2));
        assertThat(firstSessionId(replay)).isEqualTo(firstSessionId(first));

        assertThat(sessionRows(userId)).isEqualTo(2);
    }

    @Test
    void 저장된_분할_조각과_같은_시각에_시작하는_별개_제출은_재제출이_아니라_409다() {
        assertThat(submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000))
                .hasStatus(HttpStatus.CREATED);

        assertThat(submit(userId, midnight, midnight.plusSeconds(7200), 7200, 7200))
                .hasStatus(HttpStatus.CONFLICT);

        assertThat(sessionRows(userId)).isEqualTo(2);
    }

    @Test
    void 시작_시각이_다르면_별도_세션으로_저장된다() {
        assertThat(submit(userId, sessionStart, sessionEnd, 7200, 6600)).hasStatus(HttpStatus.CREATED);
        assertThat(submit(userId, sessionEnd, sessionEnd.plusSeconds(3600), 3600, 3000))
                .hasStatus(HttpStatus.CREATED);

        assertThat(sessionRows(userId)).isEqualTo(2);
    }

    @Test
    void 분할_조각이_기존_세션과_같은_시각에_시작하면_409이고_아무것도_저장되지_않는다() {
        assertThat(submit(userId, midnight, midnight.plusSeconds(3600), 3600, 3000))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult conflict = submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000);
        assertThat(conflict)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("이미 같은 시각에 시작한 세션"));

        assertThat(sessionRows(userId)).isEqualTo(1);
    }

    @Test
    void 루트를_알_수_없는_레거시_행과_시각이_겹치면_재제출이_아니라_409다() {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(sessionStart.atZone(KST).toLocalDate()),
                java.sql.Timestamp.from(sessionStart),
                java.sql.Timestamp.from(sessionEnd),
                7200,
                6600);

        assertThat(submit(userId, sessionStart, sessionEnd, 7200, 6600)).hasStatus(HttpStatus.CONFLICT);
        assertThat(sessionRows(userId)).isEqualTo(1);
    }

    @Test
    void 다른_유저는_같은_시각에_시작해도_저장된다() {
        Long otherUserId = insertUser();

        assertThat(submit(userId, sessionStart, sessionEnd, 7200, 6600)).hasStatus(HttpStatus.CREATED);
        assertThat(submit(otherUserId, sessionStart, sessionEnd, 7200, 6600)).hasStatus(HttpStatus.CREATED);

        assertThat(sessionRows(userId)).isEqualTo(1);
        assertThat(sessionRows(otherUserId)).isEqualTo(1);
    }
}
