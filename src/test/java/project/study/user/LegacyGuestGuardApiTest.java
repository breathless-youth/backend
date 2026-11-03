package project.study.user;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * 구 앱 무인증 경로(ADR-0020)는 비회원(DEVICE) userId만 받는다 (ADR-0041). 구 앱 사용자는 늘 비회원이라, 숫자 userId만 알면 소셜 계정의 기록을
 * 읽고 쓰던 구멍을 없는 사용자와 같은 404로 막는다. 비회원의 동작은 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyGuestGuardApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    private long guest;
    private long social;

    @BeforeEach
    void setUp() {
        guest = registerGuest();
        social = jdbc.sql("""
                        INSERT INTO users (provider, provider_user_id, nickname)
                        VALUES ('GOOGLE', :sub, :nick) RETURNING id""")
                .param("sub", "google-" + UUID.randomUUID())
                .param("nick", "g" + System.nanoTime() % 1_000_000_000)
                .query(Long.class)
                .single();
    }

    @Test
    void 프로필_조회와_수정은_소셜_userId면_404다() {
        assertUserNotFound(mvc.get().uri("/api/users/" + social + "/profile").exchange());
        assertUserNotFound(patchProfile(social));

        assertThat(mvc.get().uri("/api/users/" + guest + "/profile")).hasStatusOk();
        assertThat(patchProfile(guest)).hasStatusOk();
    }

    @Test
    void 세션_제출과_진행중_스냅샷은_소셜_userId면_404이고_기록이_생기지_않는다() {
        assertUserNotFound(post("/api/study-sessions", sessionBody(social)));
        assertUserNotFound(put("/api/study-sessions/active", snapshotBody(social)));
        assertThat(jdbc.sql("SELECT count(*) FROM study_session WHERE user_id = :u")
                        .param("u", social)
                        .query(Long.class)
                        .single())
                .isZero();

        assertThat(post("/api/study-sessions", sessionBody(guest))).hasStatus(HttpStatus.CREATED);
        assertThat(put("/api/study-sessions/active", snapshotBody(guest))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 세션_상세와_진행중_복원과_복구는_소셜_userId면_404다() {
        long sessionId = jdbc.sql("""
                        INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec)
                        VALUES (:u, DATE '2030-03-01', now() - interval '2 hour', now() - interval '1 hour', 60, 60)
                        RETURNING id""").param("u", social).query(Long.class).single();

        assertUserNotFound(mvc.get()
                .uri("/api/study-sessions/" + sessionId)
                .param("userId", String.valueOf(social))
                .exchange());
        assertUserNotFound(mvc.get()
                .uri("/api/study-sessions/active")
                .param("userId", String.valueOf(social))
                .exchange());
        assertUserNotFound(mvc.post()
                .uri("/api/study-sessions/recovery")
                .param("userId", String.valueOf(social))
                .exchange());
    }

    @Test
    void 통계_조회는_소셜_userId면_404다() {
        LocalDate day = LocalDate.now(KST);
        assertUserNotFound(mvc.get()
                .uri("/api/stats")
                .param("userId", String.valueOf(social))
                .param("date", day.toString())
                .exchange());
        assertUserNotFound(mvc.get()
                .uri("/api/stats/streak")
                .param("userId", String.valueOf(social))
                .exchange());
        assertUserNotFound(mvc.get()
                .uri("/api/stats/period")
                .param("userId", String.valueOf(social))
                .param("from", day.minusDays(6).toString())
                .param("to", day.toString())
                .exchange());

        assertThat(mvc.get()
                        .uri("/api/stats")
                        .param("userId", String.valueOf(guest))
                        .param("date", day.toString()))
                .hasStatusOk();
    }

    @Test
    void 방_만들기와_입장과_나가기는_소셜_userId면_404다() {
        assertUserNotFound(post("/api/rooms", "{\"userId\": " + social + "}"));
        MvcTestResult created = post("/api/rooms", "{\"userId\": " + guest + "}");
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String inviteCode = objectMapper
                .readTree(created.getResponse().getContentAsByteArray())
                .get("inviteCode")
                .asString();
        long roomId = objectMapper
                .readTree(created.getResponse().getContentAsByteArray())
                .get("roomId")
                .asLong();

        assertUserNotFound(
                post("/api/rooms/join", "{\"userId\": " + social + ", \"inviteCode\": \"" + inviteCode + "\"}"));
        assertUserNotFound(mvc.post()
                .uri("/api/rooms/" + roomId + "/leave")
                .param("userId", String.valueOf(social))
                .exchange());
    }

    @Test
    void RTC_통계는_소셜_userId면_404이고_행이_생기지_않는다() {
        String connectionId = "legacy-guard-" + UUID.randomUUID();
        String body = """
                {"connectionId":"%s","roomId":1,"userId":%d,"candidateType":"host","isFinal":true}""";

        assertUserNotFound(post("/api/rtc-stats", body.formatted(connectionId, social)));
        assertThat(jdbc.sql("SELECT count(*) FROM rtc_connection_stat WHERE connection_id = :c")
                        .param("c", connectionId)
                        .query(Long.class)
                        .single())
                .isZero();

        assertThat(post("/api/rtc-stats", body.formatted(connectionId, guest))).hasStatus(HttpStatus.NO_CONTENT);
    }

    private long registerGuest() {
        MvcTestResult result = post("/api/users", "{\"deviceId\": \"" + UUID.randomUUID() + "\"}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    private MvcTestResult patchProfile(long userId) {
        return mvc.patch()
                .uri("/api/users/" + userId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\": \"합격\"}")
                .exchange();
    }

    private String sessionBody(long userId) {
        Instant start =
                LocalDate.now(KST).minusDays(1).atStartOfDay(KST).plusHours(1).toInstant();
        return """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": 3000, "focusSec": 3000, "events": []}""".formatted(userId, start, start.plusSeconds(3600));
    }

    private String snapshotBody(long userId) {
        Instant start = Instant.now().minusSeconds(1800);
        return """
                {"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": 600, "focusSec": 540, "events": []}""".formatted(userId, start, start.plusSeconds(600));
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult put(String uri, String body) {
        return mvc.put()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertUserNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("USER_NOT_FOUND");
    }
}
