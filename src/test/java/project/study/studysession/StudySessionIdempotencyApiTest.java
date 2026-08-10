package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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

    // 기준 날짜를 한 번만 읽어 고정 — 테스트 도중 KST 자정이 지나도 입력과 기대값이 같은 기준을 쓴다
    private final LocalDate today = LocalDate.now(KST);

    // 미래 시각 검증과 자정 분할을 모두 피하도록 어제 KST 낮 12~14시의 세션을 만든다
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    // 어제 KST 자정 — 자정 분할·시작 시각 충돌 테스트의 기준점
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

    private static RequestPostProcessor authenticatedUser(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private MvcTestResult submit(Long userId, Instant startedAt, Instant endedAt, int studySec, int focusSec) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(startedAt, endedAt, studySec, focusSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(authenticatedUser(userId))
                .exchange();
    }

    private long firstSessionId(MvcTestResult result) {
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();
    }

    private Integer sessionRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 같은_시각으로_재제출하면_기존_세션을_그대로_반환하고_중복_저장하지_않는다() {
        MvcTestResult first = submit(userId, sessionStart, sessionEnd, 7200, 6600);
        assertThat(first).hasStatus(HttpStatus.CREATED);

        // 재제출 본문의 studySec/focusSec이 달라도 저장된 원본이 그대로 내려온다 (멱등 키만 본다)
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
        // 강제종료 복구 재전송에서 endedAt이 어긋나도, 멱등 키(루트 startedAt)만 보고 저장된 원본을 그대로 돌려준다
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
        // 자정을 걸친 제출이 만든 뒤 조각(자정 시작)은 루트가 아니다 — 자정에 시작하는 별개 제출을
        // 재제출로 오인해 201로 조각을 돌려주면 안 되고, 시작 시각 충돌(409)로 거절해야 한다
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
        // 정확히 자정에 시작하는 세션을 먼저 저장 — 이후 자정을 걸친 제출의 뒤 조각(자정 시작)과 충돌한다
        assertThat(submit(userId, midnight, midnight.plusSeconds(3600), 3600, 3000))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult conflict = submit(userId, midnight.minusSeconds(3600), midnight.plusSeconds(3600), 7200, 6000);
        assertThat(conflict)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().contains("이미 같은 시각에 시작한 세션"));

        // 충돌한 제출은 통째로 롤백된다 — 앞 조각(23시 시작)도 저장되면 안 된다
        assertThat(sessionRows(userId)).isEqualTo(1);
    }

    @Test
    void 루트를_알_수_없는_레거시_행과_시각이_겹치면_재제출이_아니라_409다() {
        // V7 이전 데이터: submission_started_at이 NULL인 행 — 재제출 판별에서 제외되어야 한다
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(sessionStart.atZone(KST).toLocalDate()),
                java.sql.Timestamp.from(sessionStart),
                java.sql.Timestamp.from(sessionEnd),
                7200,
                6600);

        // 같은 시각 제출이 레거시 행을 재제출로 오인해 201을 주면 안 되고, 유니크 제약이 409로 거절해야 한다
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
