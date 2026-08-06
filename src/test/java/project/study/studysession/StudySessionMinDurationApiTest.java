package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 세션은 길이와 무관하게 저장되지만, 조회(순공시간 1분 미만 제외)와 스트릭(순공시간 10분 이상인 세션이
 * 그 날 하나라도 있어야 인정 — 세션 단위 기준, 하루 합계가 아니다)은 서로 다른 기준으로 걸러낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 인증은 MVP 제외 (ADR-0004) — 재도입 시 @WithMockUser 등으로 인증 컨텍스트 추가 필요
class StudySessionMinDurationApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    // 미래 시각 검증과 자정 분할을 모두 피하도록 어제 KST 낮 12시 시작 세션을 쓴다
    private final LocalDate yesterday = LocalDate.now(KST).minusDays(1);
    private final Instant sessionStart =
            yesterday.atStartOfDay(KST).plusHours(12).toInstant();

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MockMvcTester.MockMvcRequestBuilder submitRequest(Instant startedAt, int durationSec) {
        String body = """
                {"userId": %s, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(userId, startedAt, startedAt.plusSeconds(durationSec), durationSec, durationSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private int sessionRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 몇_초짜리_세션도_저장된다() {
        assertThat(submitRequest(sessionStart, 5)).hasStatus(HttpStatus.CREATED);

        assertThat(sessionRowCount()).isEqualTo(1);
    }

    @Test
    void 순공시간_1분_미만_세션은_저장되지만_목록조회에는_안_잡힌다() {
        assertThat(submitRequest(sessionStart, 59)).hasStatus(HttpStatus.CREATED);
        assertThat(sessionRowCount()).isEqualTo(1);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", userId.toString())
                        .param("date", yesterday.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 순공시간_1분_이상_10분_미만_세션은_목록엔_잡히지만_스트릭엔_반영되지_않는다() {
        assertThat(submitRequest(sessionStart, 599)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", userId.toString())
                        .param("date", yesterday.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1));

        assertThat(mvc.get().uri("/api/stats/streak").param("userId", userId.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 순공시간_10분_이상_세션_하나만_있어도_그날_스트릭에_잡힌다() {
        // 짧은 세션(9분59초) + 긴 세션(10분) — 하루 합계가 아니라 세션 단위 기준이므로 긴 세션 하나로 충분하다
        assertThat(submitRequest(sessionStart, 599)).hasStatus(HttpStatus.CREATED);
        assertThat(submitRequest(sessionStart.plusSeconds(3600), 600)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get().uri("/api/stats/streak").param("userId", userId.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.streak", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void from_to_기간을_주면_스트릭_인정_기준을_만족한_날짜만_studiedDatesInRange에_담긴다() {
        assertThat(submitRequest(sessionStart, 600)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats/streak")
                        .param("userId", userId.toString())
                        .param("from", yesterday.minusDays(5).toString())
                        .param("to", yesterday.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.studiedDatesInRange",
                        dates -> assertThat(dates)
                                .asInstanceOf(InstanceOfAssertFactories.LIST)
                                .containsExactly(yesterday.toString()));
    }

    @Test
    void from_to를_주지_않으면_studiedDatesInRange는_빈_배열이다() {
        assertThat(mvc.get().uri("/api/stats/streak").param("userId", userId.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.studiedDatesInRange",
                        dates -> assertThat(dates)
                                .asInstanceOf(InstanceOfAssertFactories.LIST)
                                .isEmpty());
    }

    @Test
    void from만_주고_to를_주지_않으면_400이다() {
        assertThat(mvc.get()
                        .uri("/api/stats/streak")
                        .param("userId", userId.toString())
                        .param("from", yesterday.toString()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void from이_to보다_이후이면_400이다() {
        assertThat(mvc.get()
                        .uri("/api/stats/streak")
                        .param("userId", userId.toString())
                        .param("from", yesterday.toString())
                        .param("to", yesterday.minusDays(1).toString()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
