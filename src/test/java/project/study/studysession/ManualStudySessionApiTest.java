package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.studysession.dto.ManualStudySessionRequest;
import project.study.studysession.service.ManualStudySessionService;

/** 수동 기록 API: 검증·겹침 자르기·자정 분할·묶음 수정·삭제 (ADR-0031). 집계 반영은 ManualStudySessionStatsApiTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualStudySessionApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ManualStudySessionService manualStudySessionService;

    private long userId;

    // 실제 시계를 쓰므로 미래 판정에 걸리지 않게 이틀 전을 쓴다
    private final LocalDate day = LocalDate.now(KST).minusDays(2);

    @BeforeEach
    void createUser() {
        userId = newUser();
    }

    private long newUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KST).toInstant();
    }

    private Instant at(int hour, int minute) {
        return at(day, hour, minute);
    }

    private static String body(Instant start, Instant end, Long subjectId) {
        return """
                {"startedAt": "%s", "endedAt": "%s", "subjectId": %s}""".formatted(start, end, subjectId);
    }

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder post(long user, Instant start, Instant end, Long subjectId) {
        return mvc.post()
                .uri("/api/study-sessions/manual")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(start, end, subjectId))
                .with(asUser(user));
    }

    private MockMvcRequestBuilder post(Instant start, Instant end) {
        return post(userId, start, end, null);
    }

    private MockMvcRequestBuilder patch(long user, long id, Instant start, Instant end, Long subjectId) {
        return mvc.patch()
                .uri("/api/study-sessions/manual/{id}", id)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(start, end, subjectId))
                .with(asUser(user));
    }

    private MockMvcRequestBuilder delete(long user, long id) {
        return mvc.delete()
                .uri("/api/study-sessions/manual/{id}", id)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(user));
    }

    /** 측정 세션 제출 — 총공부는 길이 전체, 순공은 focusSec. */
    private void measured(Instant start, Instant end, int focusSec) {
        int length = (int) (end.getEpochSecond() - start.getEpochSecond());
        String request = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(start, end, length, focusSec);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);
    }

    private long subject(String name) {
        return jdbc.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, userId, name);
    }

    private List<Long> manualIds() {
        return jdbc.queryForList(
                "SELECT id FROM study_session WHERE user_id = ? AND source = 'MANUAL' ORDER BY started_at",
                Long.class,
                userId);
    }

    private List<Instant> manualSubmissionKeys() {
        return jdbc
                .queryForList(
                        "SELECT DISTINCT submission_started_at FROM study_session WHERE user_id = ? AND source = 'MANUAL'",
                        Timestamp.class,
                        userId)
                .stream()
                .map(Timestamp::toInstant)
                .toList();
    }

    @Test
    void 추가하면_분_단위로_맞춘_이벤트_없는_수동_세션으로_저장한다() {
        long math = subject("수학");

        assertThat(post(userId, at(14, 0).plusSeconds(30), at(15, 30).plusSeconds(45), math))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].source", v -> assertThat(v).isEqualTo("MANUAL"))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(5400))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(5400))
                .hasPathSatisfying("$[0].events.length()", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].subjectId", v -> assertThat(v).isEqualTo((int) math));
        assertThat(manualSubmissionKeys()).containsExactly(at(14, 0));
        Integer slotRows = jdbc.queryForObject(
                "SELECT count(*) FROM study_session_slot sl JOIN study_session s ON s.id = sl.session_id"
                        + " WHERE s.user_id = ?",
                Integer.class,
                userId);
        assertThat(slotRows).isZero();
    }

    @Test
    void 자정을_넘기면_조각_둘이_첫_조각의_시작_시각을_공유한다() {
        assertThat(post(at(23, 0), at(day.plusDays(1), 1, 0)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$[0].statDate", v -> assertThat(v).isEqualTo(day.toString()))
                .hasPathSatisfying(
                        "$[1].statDate",
                        v -> assertThat(v).isEqualTo(day.plusDays(1).toString()));
        assertThat(manualSubmissionKeys()).containsExactly(at(23, 0));
    }

    @Test
    void 일부_겹치면_겹친_구간만_빼고_남는_조각을_저장한다() {
        measured(at(14, 30), at(15, 0), 1800);

        assertThat(post(at(14, 0), at(16, 0)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying("$[1].studySec", v -> assertThat(v).isEqualTo(3600));
        assertThat(manualSubmissionKeys()).containsExactly(at(14, 0));
    }

    @Test
    void 측정_경계의_초는_안쪽_분으로_맞추고_1분_미만_조각은_버린다() {
        // 앞 조각 14:00~14:00:40은 1분 미만이라 버리고, 뒤 조각은 14:31:20을 14:32로 올려 맞춘다
        measured(at(14, 0).plusSeconds(40), at(14, 31).plusSeconds(20), 1800);

        assertThat(post(at(14, 0), at(15, 0)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(28 * 60));
    }

    @Test
    void 끝이_닿는_기록은_겹침이_아니다() {
        measured(at(14, 0), at(15, 0), 3600);

        assertThat(post(at(15, 0), at(16, 0)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(3600));
    }

    @Test
    void 전부_겹치면_409이고_같은_값을_다시_보내도_409다() {
        measured(at(10, 0), at(12, 0), 3600);
        assertThat(post(at(10, 30), at(11, 30))).hasStatus(HttpStatus.CONFLICT);

        assertThat(post(at(14, 0), at(15, 0))).hasStatus(HttpStatus.CREATED);
        assertThat(post(at(14, 0), at(15, 0))).hasStatus(HttpStatus.CONFLICT);
        assertThat(manualIds()).hasSize(1);
    }

    @Test
    void 일분_미만_미래_24시간_초과는_400이다() {
        assertThat(post(at(14, 0), at(14, 0).plusSeconds(59))).hasStatus(HttpStatus.BAD_REQUEST);
        Instant now = Instant.now();
        assertThat(post(now.minusSeconds(3600), now.plusSeconds(180))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(at(0, 0), at(day.plusDays(1), 0, 1))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(manualIds()).isEmpty();
    }

    @Test
    void 남의_과목이나_새로_고른_지운_과목은_400이다() {
        long deleted = subject("지운 과목");
        jdbc.update("UPDATE study_subject SET deleted_at = now() WHERE id = ?", deleted);
        long others = jdbc.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, newUser(), "남의 것");

        assertThat(post(userId, at(14, 0), at(15, 0), deleted)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(userId, at(14, 0), at(15, 0), others)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 수정은_묶음을_다시_만들고_자기_묶음과는_겹침으로_보지_않는다() {
        long math = subject("수학");
        assertThat(post(userId, at(23, 0), at(day.plusDays(1), 1, 0), math)).hasStatus(HttpStatus.CREATED);
        List<Long> oldIds = manualIds();
        // 지운 과목이어도 묶음의 지금 과목을 그대로 다시 보내는 것은 받는다
        jdbc.update("UPDATE study_subject SET deleted_at = now() WHERE id = ?", math);

        assertThat(patch(userId, oldIds.get(1), at(22, 30), at(23, 30), math))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[0].source", v -> assertThat(v).isEqualTo("MANUAL"));
        assertThat(manualIds()).hasSize(1).doesNotContainAnyElementsOf(oldIds);
        assertThat(manualSubmissionKeys()).containsExactly(at(22, 30));
    }

    @Test
    void 수정도_겹침을_자르고_전부_겹치면_409에_묶음은_그대로다() {
        measured(at(10, 0), at(11, 0), 3600);
        assertThat(post(at(14, 0), at(15, 0))).hasStatus(HttpStatus.CREATED);
        long id = manualIds().getFirst();

        assertThat(patch(userId, id, at(10, 0), at(10, 30), null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(manualIds()).containsExactly(id);
    }

    @Test
    void 삭제는_어느_조각으로_불러도_묶음_전체를_지운다() {
        assertThat(post(at(23, 0), at(day.plusDays(1), 1, 0))).hasStatus(HttpStatus.CREATED);
        List<Long> ids = manualIds();
        assertThat(ids).hasSize(2);

        assertThat(delete(userId, ids.getFirst())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(manualIds()).isEmpty();
    }

    @Test
    void 측정_세션_id로_수정_삭제하면_409다() {
        measured(at(10, 0), at(11, 0), 3600);
        long measuredId = jdbc.queryForObject("SELECT id FROM study_session WHERE user_id = ?", Long.class, userId);

        assertThat(patch(userId, measuredId, at(14, 0), at(15, 0), null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(delete(userId, measuredId)).hasStatus(HttpStatus.CONFLICT);
        Integer rows =
                jdbc.queryForObject("SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 남의_기록이면_404다() {
        assertThat(post(at(14, 0), at(15, 0))).hasStatus(HttpStatus.CREATED);
        long id = manualIds().getFirst();
        long stranger = newUser();

        assertThat(patch(stranger, id, at(14, 0), at(15, 0), null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(stranger, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 측정_세션_제출은_수동_기록과_겹쳐도_받는다() {
        assertThat(post(at(14, 0), at(16, 0))).hasStatus(HttpStatus.CREATED);

        measured(at(14, 30), at(15, 0), 1500);
    }

    @Test
    void 토큰_없이_추가하면_401이다() {
        assertThat(mvc.post()
                        .uri("/api/study-sessions/manual")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(at(14, 0), at(15, 0), null)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(manualIds()).isEmpty();
    }

    @Test
    void 먼저_시작한_측정과_겹치면_앞이_잘리고_첫_조각_시작이_묶음_키가_된다() {
        measured(at(13, 0), at(14, 30), 3600);

        assertThat(post(at(14, 0), at(16, 0)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(5400));
        assertThat(manualSubmissionKeys()).containsExactly(at(14, 30));
    }

    @Test
    void 앞이_잘린_두_요청은_서로_다른_묶음이라_하나를_지워도_다른_하나는_남는다() {
        measured(at(14, 0), at(14, 30), 1800);
        assertThat(post(at(14, 0), at(16, 0))).hasStatus(HttpStatus.CREATED);
        assertThat(post(at(14, 0), at(17, 0))).hasStatus(HttpStatus.CREATED);
        assertThat(manualSubmissionKeys()).containsExactlyInAnyOrder(at(14, 30), at(16, 0));

        assertThat(delete(userId, manualIds().getFirst())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(manualIds()).hasSize(1);
        assertThat(manualSubmissionKeys()).containsExactly(at(16, 0));
        Instant endedAt = jdbc.queryForObject(
                        "SELECT ended_at FROM study_session WHERE user_id = ? AND source = 'MANUAL'",
                        Timestamp.class,
                        userId)
                .toInstant();
        assertThat(endedAt).isEqualTo(at(17, 0));
    }

    @Test
    void 같은_사용자의_겹치는_두_요청이_동시에_와도_저장된_수동_기록은_겹치지_않는다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> createAfter(start, at(10, 0), at(12, 0)));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> createAfter(start, at(11, 0), at(13, 0)));
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        // 겹친 1시간을 두 번 세지 않아 합이 4시간이 아니라 3시간이다
        Integer totalSec = jdbc.queryForObject(
                "SELECT SUM(study_sec) FROM study_session WHERE user_id = ? AND source = 'MANUAL'",
                Integer.class,
                userId);
        assertThat(totalSec).isEqualTo(3 * 3600);
    }

    private void createAfter(CountDownLatch start, Instant from, Instant to) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        manualStudySessionService.create(userId, new ManualStudySessionRequest(from, to, null));
    }
}
