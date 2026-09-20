package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import project.study.studysession.buffer.ActiveSnapshotBuffer;

/** BY-447 진행 스냅샷 보고 API — 30초마다 오는 누적 스냅샷의 UPSERT·역순 무시·검증을 다룬다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 스냅샷은 항상 코얼레싱 버퍼를 거쳐 저장된다(BY-470, 즉시 UPSERT 경로 없음). 이 테스트는 보고 직후
// buffer.flush()를 직접 호출해 DB 반영을 결정적으로 만든다. 버퍼링(코얼레싱·역순 무시)은 ActiveSnapshotBufferTest에서 따로 검증한다.
class ActiveSessionSnapshotApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    private Long userId;

    // 검증(미래 시각 금지)이 실제 시계를 쓰므로 항상 과거인 시각을 기준으로 잡는다
    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    // findByLastSeenAtBefore(스케줄러 조회)가 전 유저 대상 풀스캔이라, 이 테스트가 남긴 draft가 커밋된 채
    // 남으면 다른 테스트 클래스(특히 ActiveSessionFinalizeTest)의 스캔 결과를 오염시킨다 — 매 테스트 뒤 정리한다.
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private MvcTestResult report(Long uid, Instant started, Instant reported, int studySec, int focusSec) {
        return report(uid, started, reported, studySec, focusSec, "[]");
    }

    private MvcTestResult report(
            Long uid, Instant started, Instant reported, int studySec, int focusSec, String eventsJson) {
        String body = """
				{"startedAt": "%s", "reportedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s}""".formatted(started, reported, studySec, focusSec, eventsJson);
        MvcTestResult result = mvc.put()
                .uri("/api/study-sessions/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(uid))
                .exchange();
        buffer.flush(); // 버퍼에 들어간 스냅샷을 지금 DB에 반영한다 — 검증 실패(400)면 버퍼가 비어 있어 no-op
        return result;
    }

    private Integer draftRows(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    private Integer draftStudySec(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT study_sec FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void 첫_스냅샷은_draft를_만든다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(draftRows(userId)).isEqualTo(1);
    }

    @Test
    void 두번째_스냅샷은_행을_늘리지_않고_덮어쓴다() {
        report(userId, startedAt, startedAt.plusSeconds(30), 30, 30);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 60, 55)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftRows(userId)).isEqualTo(1);
        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 저장된_것보다_과거_reportedAt은_조용히_무시된다() {
        report(userId, startedAt, startedAt.plusSeconds(60), 60, 55);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 이벤트_스냅샷이_jsonb로_저장된다() {
        String events = """
				[{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(10), startedAt.plusSeconds(20));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 20, 10, events))
                .hasStatus(HttpStatus.NO_CONTENT);

        String stored = jdbcTemplate.queryForObject(
                "SELECT events::text FROM active_study_session WHERE user_id = ?", String.class, userId);
        assertThat(stored).contains("PHONE");
    }

    @Test
    void SLEEP_이벤트가_실린_스냅샷도_저장되고_재접속_조회에_그대로_내려온다() {
        // DB(마이크로초)·JSON 직렬화 왕복에서 표기가 어긋나지 않도록 초 단위로 자른 시각을 쓴다
        Instant started = startedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String events = """
				[{"status":"SLEEP","startedAt":"%s","endedAt":"%s"}]""".formatted(started.plusSeconds(10), started.plusSeconds(20));
        assertThat(report(userId, started, started.plusSeconds(30), 20, 10, events))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(restore(userId))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.events[0].status", v -> assertThat(v).isEqualTo("SLEEP"));
    }

    @Test
    void reportedAt이_startedAt_이전이면_400이다() {
        assertThat(report(userId, startedAt, startedAt, 0, 0)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(draftRows(userId)).isEqualTo(0);
    }

    @Test
    void focusSec이_studySec을_넘으면_400이다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 40)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이벤트가_세션_구간_밖이면_400이다() {
        String events = """
				[{"status":"AWAY","startedAt":"%s","endedAt":"%s"}]""".formatted(startedAt.plusSeconds(50), startedAt.plusSeconds(90));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 20, events))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // 없는 user_id의 판정은 버퍼 flush(비동기)로 옮겨졌다 — 요청은 받아들이고(하트비트라 실질 영향 없음),
    // flush의 행별 폴백이 FK 위반 행만 건너뛰어 draft가 남지 않는다 (ActiveStudySessionService.reportSnapshot 참고)
    @Test
    void 존재하지_않는_유저의_스냅샷은_요청은_받되_flush에서_버려진다() {
        assertThat(report(999_999L, startedAt, startedAt.plusSeconds(30), 30, 30))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(draftRows(999_999L)).isEqualTo(0);
    }

    @Test
    void 필수값_누락은_400이다() {
        String body = """
				{"startedAt": "%s"}""".formatted(startedAt);
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId))
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ── 복구 조회 (BY-448) ──────────────────────────────────────────

    private MvcTestResult restore(Long uid) {
        return mvc.get().uri("/api/study-sessions/active").with(asUser(uid)).exchange();
    }

    @Test
    void 재접속_조회는_저장된_최신_스냅샷을_돌려준다() {
        // DB(마이크로초)·JSON 직렬화 왕복에서 표기가 어긋나지 않도록 초 단위로 자른 시각을 쓴다
        Instant started = startedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String events = """
				[{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]""".formatted(started.plusSeconds(10), started.plusSeconds(20));
        report(userId, started, started.plusSeconds(30), 20, 10, events);
        report(userId, started, started.plusSeconds(60), 50, 40, events);

        assertThat(restore(userId))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.startedAt", v -> assertThat(v).isEqualTo(started.toString()))
                .hasPathSatisfying(
                        "$.reportedAt",
                        v -> assertThat(v).isEqualTo(started.plusSeconds(60).toString()))
                .hasPathSatisfying("$.studySec", v -> assertThat(v).isEqualTo(50))
                .hasPathSatisfying("$.focusSec", v -> assertThat(v).isEqualTo(40))
                .hasPathSatisfying("$.events[0].status", v -> assertThat(v).isEqualTo("PHONE"));
    }

    @Test
    void 진행중_draft가_없으면_조회는_404다() {
        assertThat(restore(userId)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void draft가_여러_개면_마지막_보고가_가장_최근인_것을_돌려준다() {
        // 앱이 죽고 새 세션을 시작하면 옛 draft(확정 대기)와 새 draft가 잠깐 공존한다 — 복구는 최신 것이어야 한다
        Instant oldStarted = startedAt.minusSeconds(20_000);
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
                userId,
                java.sql.Timestamp.from(oldStarted),
                java.sql.Timestamp.from(oldStarted.plusSeconds(600)),
                java.sql.Timestamp.from(startedAt.minusSeconds(19_000)),
                999,
                999);
        report(userId, startedAt, startedAt.plusSeconds(30), 30, 30);

        assertThat(restore(userId))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.studySec", v -> assertThat(v).isEqualTo(30));
    }
}
