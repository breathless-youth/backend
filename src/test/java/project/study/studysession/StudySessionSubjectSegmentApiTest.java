package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import project.study.config.ApiVersionConfig;
import project.study.studysession.buffer.ActiveSnapshotBuffer;
import project.study.studysession.service.ActiveStudySessionService;

/** BY-733 과목 구간 계약 — 제출·스냅샷·복구·자동 확정의 subjectSegments 저장·검증·자정 절단·파생 계산·누적 합산 (ADR-0023). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionSubjectSegmentApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

    private Long userId;
    private Long subjectId;

    private final LocalDate today = LocalDate.now(KST);
    // 어제 12:00~14:00 (KST) — 항상 과거라 미래 검증에 걸리지 않는다
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = insertUser();
        subjectId = insertSubject(userId, "수학");
    }

    // 스케줄러 풀스캔 오염 방지 — ActiveSessionSnapshotApiTest와 같은 이유
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Long insertSubject(Long ownerId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, ownerId, name);
    }

    private static String segment(Long subject, Instant start, Instant end) {
        return """
                {"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}""".formatted(subject, start, end);
    }

    private static String event(String status, Instant start, Instant end) {
        return """
                {"status": "%s", "startedAt": "%s", "endedAt": "%s"}""".formatted(status, start, end);
    }

    private MvcTestResult submit(
            Instant start, Instant end, int studySec, int focusSec, String eventsJson, String segmentsJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s, "subjectSegments": %s}""".formatted(start, end, studySec, focusSec, eventsJson, segmentsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    private MvcTestResult snapshot(Instant started, Instant reported, String segmentsJson) {
        String body = """
                {"startedAt": "%s", "reportedAt": "%s", "studySec": 7000, "focusSec": 6500, "events": [], "subjectSegments": %s}""".formatted(started, reported, segmentsJson);
        return mvc.put()
                .uri("/api/study-sessions/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    @Test
    void 세션_제출의_과목_구간이_저장되고_서버가_계산한_값이_과목_누적에_합산된다() {
        // 12:00~13:00 수학, 13:00~13:30 수학(뒤섞어 보냄). PHONE 12:10~12:20(10분)은 첫 구간 순공에서만 빠진다
        String events = "[" + event("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200)) + "]";
        String segments = "[" + segment(subjectId, sessionStart.plusSeconds(3600), sessionStart.plusSeconds(5400)) + ","
                + segment(subjectId, sessionStart, sessionStart.plusSeconds(3600)) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, events, segments))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].subjectSegments.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].startedAt", v -> assertThat(v).isEqualTo(sessionStart.toString()))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].studySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].focusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying(
                        "$[0].subjectSegments[1].studySec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying(
                        "$[0].subjectSegments[1].focusSec", v -> assertThat(v).isEqualTo(1800));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_subject_segment WHERE subject_id = ?", Integer.class, subjectId);
        assertThat(rows).isEqualTo(2);

        assertThat(mvc.get()
                        .uri("/api/subjects")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(5400))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(4800));
    }

    @Test
    void 과목_구간_없이_제출하면_기존처럼_저장된다() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": []}""".formatted(sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].subjectSegments.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 과목_구간이_겹치면_400이고_세션도_저장되지_않는다() {
        String segments = "[" + segment(subjectId, sessionStart, sessionStart.plusSeconds(3600)) + ","
                + segment(subjectId, sessionStart.plusSeconds(3000), sessionStart.plusSeconds(5400)) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, "[]", segments)).hasStatus(HttpStatus.BAD_REQUEST);

        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
    }

    @Test
    void 다른_사용자의_과목이면_400이다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");

        assertThat(submit(
                        sessionStart,
                        sessionEnd,
                        7200,
                        6600,
                        "[]",
                        "[" + segment(otherSubjectId, sessionStart, sessionStart.plusSeconds(600)) + "]"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 자정을_넘는_제출은_과목_구간도_자정에서_잘려_두_조각에_담긴다() {
        // 그저께 23:00 ~ 어제 01:00 (KST), 구간 23:30~00:30
        Instant start = today.minusDays(2).atStartOfDay(KST).plusHours(23).toInstant();
        Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();
        Instant end = start.plusSeconds(7200);

        assertThat(submit(
                        start,
                        end,
                        7200,
                        6000,
                        "[]",
                        "[" + segment(subjectId, start.plusSeconds(1800), midnight.plusSeconds(1800)) + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].endedAt", v -> assertThat(v).isEqualTo(midnight.toString()))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].studySec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying(
                        "$[1].subjectSegments[0].startedAt", v -> assertThat(v).isEqualTo(midnight.toString()))
                .hasPathSatisfying(
                        "$[1].subjectSegments[0].focusSec", v -> assertThat(v).isEqualTo(1800));
    }

    @Test
    void 스냅샷의_과목_구간은_복구_조회에_시작_시각_순으로_돌아온다() {
        Long englishId = insertSubject(userId, "영어");
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant reported = started.plusSeconds(7000);
        // 나중 구간(영어)을 먼저 보내도 복구는 시작 순이다 — 앱이 마지막 원소의 과목으로 선택 상태를 복원한다
        String segments = "[" + segment(englishId, started.plusSeconds(3600), started.plusSeconds(5400)) + ","
                + segment(subjectId, started, started.plusSeconds(3600)) + "]";

        assertThat(snapshot(started, reported, segments)).hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush();

        assertThat(mvc.get().uri("/api/study-sessions/active").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.subjectSegments.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.subjectSegments[0].subjectId", v -> assertThat(v).isEqualTo(subjectId.intValue()))
                .hasPathSatisfying(
                        "$.subjectSegments[1].subjectId", v -> assertThat(v).isEqualTo(englishId.intValue()))
                .hasPathSatisfying(
                        "$.subjectSegments[1].endedAt",
                        v -> assertThat(v).isEqualTo(started.plusSeconds(5400).toString()));
    }

    @Test
    void 스냅샷이_reportedAt_밖의_구간을_담으면_400이다() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant reported = started.plusSeconds(7000);

        assertThat(snapshot(started, reported, "[" + segment(subjectId, started, reported.plusSeconds(30)) + "]"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 나노초_보고_시각으로_닫은_구간도_자동_확정본에_그대로_확정된다() {
        // DB timestamptz는 마이크로초라 reportedAt의 나노초는 버려진다. 구간 끝을 같은 나노초 시각으로 닫아 보내도
        // 요청에서 마이크로초로 절삭되므로 확정 때 "세션 밖"으로 오판되지 않는다 (Codex 리뷰 P2)
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant reported = started.plusSeconds(7000).plusNanos(100);
        assertThat(snapshot(started, reported, "[" + segment(subjectId, started, reported) + "]"))
                .hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush();
        Long draftId = jdbcTemplate.queryForObject(
                "SELECT id FROM active_study_session WHERE user_id = ?", Long.class, userId);

        activeStudySessionService.finalizeDraft(draftId);

        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM study_session_subject_segment seg
                JOIN study_session s ON s.id = seg.session_id
                WHERE s.user_id = ? AND s.auto_finalized""", Integer.class, userId);
        assertThat(rows).isEqualTo(1);
    }
}
