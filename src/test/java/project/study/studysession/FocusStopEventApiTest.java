package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 나중에 더한 순공 정지 상태(졸음 SLEEP, 화면 응시 SCREEN, 자세 이탈 POSTURE)의 세션 제출·조회 API.
 *
 * <p>StudySessionApiTest에서 분리했다(checkstyle FileLength 400줄 제약). 두 상태가 AWAY와 같은 성격이라는
 * 계산 쪽 검증은 StudySessionMidnightSplitTest가 맡고, 여기서는 값이 계약에 실제로 실리는지만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FocusStopEventApiTest {

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

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult submit(int studySec, int focusSec, String eventsJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s}""".formatted(sessionStart, sessionEnd, studySec, focusSec, eventsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private String eventJson(String status, Instant startedAt, Instant endedAt) {
        return """
                {"status": "%s", "startedAt": "%s", "endedAt": "%s"}""".formatted(status, startedAt, endedAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLEEP", "SCREEN", "POSTURE"})
    void 순공_정지_이벤트가_섞인_세션도_저장되고_응답에_그대로_내려온다(String status) {
        String events = "["
                + eventJson("AWAY", sessionStart.plusSeconds(600), sessionStart.plusSeconds(720))
                + ","
                + eventJson(status, sessionStart.plusSeconds(1800), sessionStart.plusSeconds(2280))
                + "]";

        MvcTestResult result = submit(7200, 6600, events);
        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].events[1].status", v -> assertThat(v).isEqualTo(status));

        long sessionId = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();
        List<String> savedStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM status_event WHERE session_id = ? ORDER BY started_at", String.class, sessionId);
        assertThat(savedStatuses).containsExactly("AWAY", status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLEEP", "SCREEN", "POSTURE"})
    void 상태별_이벤트_건수에_순공_정지_키가_0이라도_포함된다(String status) {
        // 프론트가 키 존재를 가정하고 칩을 계산한다 — 그 상태가 없는 세션에도 키는 있어야 한다
        assertThat(submit(7200, 7200, "[]")).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .with(asUser(userId))
                        .param("date", today.minusDays(1).toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.totalEventCounts." + status, v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$.sessions[0].eventCounts." + status,
                        v -> assertThat(v).isEqualTo(0));
    }
}
