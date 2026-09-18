package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.buffer.ActiveSnapshotBuffer;
import tools.jackson.databind.ObjectMapper;

/**
 * 구 앱(v1.2.x) 세션·통계 계약 — 헤더·토큰 없이 본문/쿼리의 userId로 식별한다 (ADR-0020).
 * 세션 검증 규칙은 서비스가 v2와 같으므로 여기서는 각 경로의 userId 채널이 소유자에 닿는지만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyStudySessionApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    private long userId;
    // 어제 날짜를 쓴다 — 오늘 새벽이면 endedAt이 미래(5분 허용)를 넘어 400이 난다 (Codex P2, StudySessionApiTest와 동일)
    private final LocalDate today = LocalDate.now(KST).minusDays(1);
    // 그날 새벽 1시 시작, 2시간짜리 — 자정 분할 규칙에 걸리지 않는다
    private final Instant sessionStart = today.atStartOfDay(KST).plusHours(1).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void registerUser() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\": \"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        userId = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    private MvcTestResult submit(String body) {
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String sessionBody(long uid) {
        return """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": 6600, "focusSec": 6000, "events": []}""".formatted(uid, sessionStart, sessionEnd);
    }

    @Test
    void 본문_userId로_세션을_제출하면_201이고_그_유저의_세션으로_저장된다() {
        assertThat(submit(sessionBody(userId)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].userId", v -> assertThat(v).isEqualTo((int) userId));
    }

    @Test
    void userId_없이_세션을_제출하면_400이다() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 6600, "focusSec": 6000, "events": []}""".formatted(sessionStart, sessionEnd);
        assertThat(submit(body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 쿼리_userId가_소유자일_때만_세션_상세를_볼_수_있다() {
        MvcTestResult created = submit(sessionBody(userId));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        long sessionId = objectMapper
                .readTree(created.getResponse().getContentAsByteArray())
                .get(0)
                .get("id")
                .asLong();

        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).param("userId", String.valueOf(userId)))
                .hasStatusOk();
        assertThat(mvc.get().uri("/api/study-sessions/" + sessionId).param("userId", "999999999"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 본문_userId로_진행중_스냅샷을_보고하고_쿼리_userId로_복원한다() {
        Instant startedAt = Instant.now().minus(30, ChronoUnit.MINUTES);
        String body = """
                {"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": 600, "focusSec": 540, "events": []}""".formatted(userId, startedAt, startedAt.plusSeconds(600));

        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush(); // 스냅샷은 버퍼를 거쳐 DB에 반영된다 — 복원 전에 결정적으로 비운다 (ActiveSessionSnapshotApiTest와 동일)
        assertThat(mvc.get().uri("/api/study-sessions/active").param("userId", String.valueOf(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.studySec", v -> assertThat(v).isEqualTo(600));
    }

    @Test
    void 존재하지_않는_userId의_스냅샷은_404로_거부되어_버퍼에_들어가지_않는다() {
        // 토큰 계약은 유효한 토큰이 곧 실존 유저지만 구 앱 경로는 아무 숫자나 올 수 있다 — 없는 유저를 버퍼에 넣으면
        // flush 때 FK 위반으로 배치가 통째로 롤백되고 정상 유저 행까지 개별 재시도로 밀린다 (Codex 보안 챌린지 P1)
        Instant startedAt = Instant.now().minus(30, ChronoUnit.MINUTES);
        String body = """
                {"userId": 999999999, "startedAt": "%s", "reportedAt": "%s", "studySec": 600, "focusSec": 540, "events": []}""".formatted(startedAt, startedAt.plusSeconds(600));

        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 쿼리_userId로_복구를_요청하면_draft가_없는_유저는_404다() {
        assertThat(mvc.post().uri("/api/study-sessions/recovery").param("userId", String.valueOf(userId)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 쿼리_userId로_날짜별_목록을_조회한다() {
        assertThat(submit(sessionBody(userId))).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", String.valueOf(userId))
                        .param("date", today.toString()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.sessionCount", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 쿼리_userId로_스트릭을_조회한다() {
        assertThat(mvc.get().uri("/api/stats/streak").param("userId", String.valueOf(userId)))
                .hasStatusOk();
    }

    @Test
    void 쿼리_userId로_기간_통계를_조회한다() {
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("userId", String.valueOf(userId))
                        .param("from", today.minusDays(6).toString())
                        .param("to", today.toString()))
                .hasStatusOk();
    }

    @Test
    void 통계_조회에_userId가_없으면_400이다() {
        assertThat(mvc.get().uri("/api/stats").param("date", today.toString())).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
