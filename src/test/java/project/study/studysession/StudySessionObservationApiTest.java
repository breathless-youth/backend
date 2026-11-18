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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** 세션 관찰 기록(하품 등) — 순공과 무관하게 저장되고, 겹침을 허용하며, 자정 분할·멱등을 따른다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionObservationApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    private final LocalDate today = LocalDate.now(KST);
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult submit(Instant start, Instant end, String eventsJson, String observationsJson) {
        String observations = observationsJson == null ? "" : ", \"observations\": " + observationsJson;
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": %s%s}""".formatted(start, end, eventsJson, observations);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private static String yawn(Instant start, Instant end) {
        return end == null
                ? "{\"type\": \"YAWN\", \"startedAt\": \"%s\"}".formatted(start)
                : "{\"type\": \"YAWN\", \"startedAt\": \"%s\", \"endedAt\": \"%s\"}".formatted(start, end);
    }

    /** 이 사용자의 관찰 기록을 조각 시작 순, 기록 시작 순으로 "조각순번:종류:시작:끝" 형태로 읽는다. */
    private List<String> observationRows() {
        return jdbcTemplate.query(
                "SELECT dense_rank() OVER (ORDER BY s.started_at) AS piece, o.type, o.started_at, o.ended_at"
                        + " FROM study_session_observation o JOIN study_session s ON o.session_id = s.id"
                        + " WHERE s.user_id = ? ORDER BY s.started_at, o.started_at",
                (rs, n) -> rs.getInt("piece") + ":" + rs.getString("type") + ":"
                        + rs.getTimestamp("started_at").toInstant() + ":"
                        + (rs.getTimestamp("ended_at") == null
                                ? "null"
                                : rs.getTimestamp("ended_at").toInstant()),
                userId);
    }

    @Test
    void 하품을_함께_제출하면_시작과_끝_시각이_그대로_저장된다() {
        Instant a = sessionStart.plusSeconds(600);
        Instant b = sessionStart.plusSeconds(1800);

        assertThat(submit(
                        sessionStart,
                        sessionEnd,
                        "[]",
                        "[%s, %s]".formatted(yawn(b, b.plusSeconds(3)), yawn(a, a.plusSeconds(5)))))
                .hasStatus(HttpStatus.CREATED);

        assertThat(observationRows())
                .containsExactly("1:YAWN:" + a + ":" + a.plusSeconds(5), "1:YAWN:" + b + ":" + b.plusSeconds(3));
    }

    @Test
    void 끝_시각_없이_보내면_끝은_비어_있다() {
        Instant a = sessionStart.plusSeconds(600);

        assertThat(submit(sessionStart, sessionEnd, "[]", "[%s]".formatted(yawn(a, null))))
                .hasStatus(HttpStatus.CREATED);

        assertThat(observationRows()).containsExactly("1:YAWN:" + a + ":null");
    }

    @Test
    void 끝이_시작과_같은_순간_기록도_저장된다() {
        Instant a = sessionStart.plusSeconds(600);

        assertThat(submit(sessionStart, sessionEnd, "[]", "[%s]".formatted(yawn(a, a))))
                .hasStatus(HttpStatus.CREATED);

        assertThat(observationRows()).containsExactly("1:YAWN:" + a + ":" + a);
    }

    @Test
    void 관찰_기록이_없는_구_앱_제출은_그대로_저장된다() {
        assertThat(submit(sessionStart, sessionEnd, "[]", null)).hasStatus(HttpStatus.CREATED);

        assertThat(observationRows()).isEmpty();
    }

    @Test
    void 관찰_기록끼리_겹쳐도_저장된다() {
        Instant a = sessionStart.plusSeconds(600);

        assertThat(submit(
                        sessionStart,
                        sessionEnd,
                        "[]",
                        "[%s, %s]".formatted(yawn(a, a.plusSeconds(5)), yawn(a.plusSeconds(2), a.plusSeconds(6)))))
                .hasStatus(HttpStatus.CREATED);

        assertThat(observationRows()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // 모르는 종류
                "[{\"type\": \"SNEEZE\", \"startedAt\": \"%1$s\"}]",
                // 세션 시작 전
                "[{\"type\": \"YAWN\", \"startedAt\": \"%2$s\"}]",
                // 끝이 시작보다 이르다
                "[{\"type\": \"YAWN\", \"startedAt\": \"%1$s\", \"endedAt\": \"%2$s\"}]",
                // 끝이 세션 밖
                "[{\"type\": \"YAWN\", \"startedAt\": \"%1$s\", \"endedAt\": \"%3$s\"}]",
                // 종류 없음
                "[{\"startedAt\": \"%1$s\"}]",
                // 시작 없음
                "[{\"type\": \"YAWN\"}]"
            })
    void 잘못된_관찰_기록은_400이다(String template) {
        String observations = template.formatted(
                sessionStart.plusSeconds(600), sessionStart.minusSeconds(1), sessionEnd.plusSeconds(1));

        assertThat(submit(sessionStart, sessionEnd, "[]", observations)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(observationRows()).isEmpty();
    }

    @Test
    void 하품은_순공과_상태별_건수에_영향을_주지_않는다() {
        Instant phone = sessionStart.plusSeconds(1000);
        String events = "[{\"status\": \"PHONE\", \"startedAt\": \"%s\", \"endedAt\": \"%s\"}]"
                .formatted(phone, phone.plusSeconds(600));
        // 하품 하나는 비집중 구간 안, 하나는 집중 구간에 있다
        String observations = "[%s, %s]"
                .formatted(
                        yawn(phone.plusSeconds(10), phone.plusSeconds(14)),
                        yawn(sessionStart.plusSeconds(3000), sessionStart.plusSeconds(3004)));

        assertThat(submit(sessionStart, sessionEnd, events, observations))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(6600))
                .hasPathSatisfying("$[0].events.length()", v -> assertThat(v).isEqualTo(1));

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .with(asUser(userId))
                        .param("date", today.minusDays(1).toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessions[0].focusSec", v -> assertThat(v).isEqualTo(6600))
                .hasPathSatisfying(
                        "$.sessions[0].eventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.totalEventCounts.PHONE", v -> assertThat(v).isEqualTo(1))
                .doesNotHavePath("$.sessions[0].eventCounts.YAWN");
    }

    @Test
    void 자정을_넘는_제출은_시작_시각이_속한_조각에_붙는다() {
        // 그저께 23:00 ~ 어제 01:00 (KST)
        Instant start = today.minusDays(2).atStartOfDay(KST).plusHours(23).toInstant();
        Instant end = start.plusSeconds(7200);
        Instant midnight = start.plusSeconds(3600);
        Instant before = start.plusSeconds(1800);
        Instant after = midnight.plusSeconds(1800);
        Instant straddle = midnight.minusSeconds(2);

        assertThat(submit(
                        start,
                        end,
                        "[]",
                        "[%s, %s, %s]"
                                .formatted(
                                        yawn(after, after.plusSeconds(3)),
                                        yawn(straddle, midnight.plusSeconds(3)),
                                        yawn(before, before.plusSeconds(3)))))
                .hasStatus(HttpStatus.CREATED);

        assertThat(observationRows())
                .containsExactly(
                        "1:YAWN:" + before + ":" + before.plusSeconds(3),
                        // 자정을 걸친 하품은 자르지 않고 시작한 조각에 붙는다
                        "1:YAWN:" + straddle + ":" + midnight.plusSeconds(3),
                        "2:YAWN:" + after + ":" + after.plusSeconds(3));
    }

    @Test
    void 같은_시작_시각으로_다시_제출해도_관찰_기록은_한_번만_저장된다() {
        Instant a = sessionStart.plusSeconds(600);
        String observations = "[%s]".formatted(yawn(a, a.plusSeconds(5)));

        assertThat(submit(sessionStart, sessionEnd, "[]", observations)).hasStatus(HttpStatus.CREATED);
        submit(sessionStart, sessionEnd, "[]", observations);

        assertThat(observationRows()).hasSize(1);
    }
}
