package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
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
 * 세션 제출의 출처(source) 기본값, 자율 모드 저장, 수동 거절, 일간 조회의 출처별 집계 (ADR-0049).
 * 파일 400줄 제한으로 StudySessionApiTest에서 분리했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionSourceApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;
    private final LocalDate today = LocalDate.now(KST);
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private String eventJson(String status, Instant startedAt, Instant endedAt) {
        return """
                {"status": "%s", "startedAt": "%s", "endedAt": "%s"}""".formatted(status, startedAt, endedAt);
    }

    private MockMvcTester.MockMvcRequestBuilder listRequest(LocalDate date) {
        return mvc.get().uri("/api/stats").with(asUser(userId)).param("date", date.toString());
    }

    private MockMvcTester.MockMvcRequestBuilder submitWithSource(
            Instant start, int studySec, int focusSec, String events, String source) {
        String sourceField = source == null ? "" : ", \"source\": \"%s\"".formatted(source);
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s%s}""".formatted(start, start.plusSeconds(studySec), studySec, focusSec, events, sourceField);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId));
    }

    private List<String> savedSources() {
        return jdbcTemplate.queryForList(
                "SELECT source FROM study_session WHERE user_id = ? ORDER BY started_at", String.class, userId);
    }

    @Test
    void source_없이_제출하면_MEASURED로_저장된다() {
        assertThat(submitWithSource(sessionStart, 3600, 3600, "[]", null))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$[0].source")
                .isEqualTo("MEASURED");

        assertThat(savedSources()).containsExactly("MEASURED");
    }

    @Test
    void source가_TIMER면_TIMER로_저장되고_응답에_실린다() {
        assertThat(submitWithSource(sessionStart, 3600, 3600, "[]", "TIMER"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$[0].source")
                .isEqualTo("TIMER");

        assertThat(savedSources()).containsExactly("TIMER");
    }

    @Test
    void source가_MANUAL이면_400_VALIDATION_FAILED다() {
        assertThat(submitWithSource(sessionStart, 3600, 3600, "[]", "MANUAL"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION_FAILED");

        assertThat(savedSources()).isEmpty();
    }

    @Test
    void 일간_조회는_기본_모드를_합계와_달력에_넣고_집중률과_최대_집중은_관리_모드만_본다() {
        LocalDate day = today.minusDays(1);
        Instant timerStart = sessionStart.plusSeconds(3 * 3600);
        assertThat(submitWithSource(timerStart, 7200, 7200, "[]", "TIMER")).hasStatus(HttpStatus.CREATED);

        // 자율 모드만 한 날도 달력에 보인다
        assertThat(listRequest(day))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.studiedDatesInMonth",
                        dates -> assertThat(dates)
                                .asInstanceOf(InstanceOfAssertFactories.LIST)
                                .contains(day.toString()));

        String events = "["
                + eventJson("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200))
                + ","
                + eventJson("AWAY", sessionStart.plusSeconds(1800), sessionStart.plusSeconds(2100))
                + "]";
        assertThat(submitWithSource(sessionStart, 7200, 6300, events, null)).hasStatus(HttpStatus.CREATED);

        // 집중률·최대 집중은 관리 모드만 보아 87.5와 5100이다.
        // 자율 모드까지 섞었다면 93.8과 7200이 나온다.
        assertThat(listRequest(day))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.totalStudySec", v -> assertThat(v).isEqualTo(14400))
                .hasPathSatisfying("$.totalFocusSec", v -> assertThat(v).isEqualTo(13500))
                .hasPathSatisfying("$.longestFocusSec", v -> assertThat(v).isEqualTo(5100))
                .hasPathSatisfying("$.focusRate", v -> assertThat(v).isEqualTo(87.5));
    }
}
