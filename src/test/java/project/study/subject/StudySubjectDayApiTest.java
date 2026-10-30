package project.study.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/**
 * BY-1053 날짜 기준 과목 목록과 날짜별 완료 기록 — 05시 하루 창, 그날 있던 과목 판단, 창 경계에 걸친 구간의 시간, 완료 표시·취소의
 * 멱등성과 거절 규칙. 순수 계산은 DaySubjectTimesTest, 판단 규칙은 StudySubjectServiceTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySubjectDayApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    /** 그날: 열흘 전 — 항상 과거라 세션 제출의 미래 검증과 완료 기록의 미래 거절에 걸리지 않는다. */
    private final LocalDate day = LocalDate.now(KST).minusDays(10);

    private final Instant dayStart = day.atStartOfDay(KST).plusHours(5).toInstant();
    private final Instant dayEnd = dayStart.plusSeconds(86_400);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    /** created_at은 기본값이 now()라 지난 날에 있던 과목을 만들려면 직접 넣는다. */
    private Long insertSubject(Long ownerId, String name, Instant createdAt, Instant deletedAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO study_subject (user_id, name, created_at, updated_at, deleted_at)
                VALUES (?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
                RETURNING id""",
                Long.class,
                ownerId,
                name,
                createdAt.toString(),
                createdAt.toString(),
                deletedAt == null ? null : deletedAt.toString());
    }

    /** 과목 API는 기본버전(1)이다 — asUser가 붙이는 2를 덮어쓴다. */
    private MvcTestResult subjectApi(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(ApiVersionConfig.HEADER, "1").with(asUser(userId)).exchange();
    }

    private MvcTestResult dayList(LocalDate date) {
        return subjectApi(mvc.get().uri("/api/subjects/day").param("date", date.toString()));
    }

    private MvcTestResult complete(Long subjectId, LocalDate date) {
        return subjectApi(mvc.put().uri("/api/subjects/" + subjectId + "/completions/" + date));
    }

    private MvcTestResult uncomplete(Long subjectId, LocalDate date) {
        return subjectApi(mvc.delete().uri("/api/subjects/" + subjectId + "/completions/" + date));
    }

    /** 세션 제출(토큰 계약 2)로 과목 구간을 저장한다 — 자정 분할·파생값 계산을 실제 경로로 태운다. */
    private void submitSession(Instant start, Instant end, int studySec, String eventsJson, Long subjectId) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s,
                 "subjectSegments": [{"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}]}""".formatted(start, end, studySec, studySec, eventsJson, subjectId, start, end);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId))
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 그날_있던_과목만_저장_순서와_지운_과목_순으로_내려오고_다른_날은_다르다() {
        Instant longBefore = dayStart.minusSeconds(86_400 * 5);
        Long math = insertSubject(userId, "수학", longBefore, null);
        Long english = insertSubject(userId, "영어", dayEnd.minusSeconds(1), null); // 다음 날 04:59:59 — 아직 그날
        insertSubject(userId, "다음날 과목", dayEnd, null); // 다음 날 05:00 — 그날에는 없었다
        Long goneInDay = insertSubject(userId, "그날 지움", longBefore, dayStart.plusSeconds(3600));
        insertSubject(userId, "전날 지움", longBefore, dayStart.minusSeconds(1));
        insertSubject(insertOtherUser(), "남의 과목", longBefore, null);
        jdbcTemplate.update("UPDATE study_subject SET sort_order = 1 WHERE id = ?", math);
        jdbcTemplate.update("UPDATE study_subject SET sort_order = 0 WHERE id = ?", english);

        assertThat(dayList(day))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$[0].id", v -> assertThat(v).isEqualTo(english.intValue()))
                .hasPathSatisfying("$[1].id", v -> assertThat(v).isEqualTo(math.intValue()))
                .hasPathSatisfying("$[2].id", v -> assertThat(v).isEqualTo(goneInDay.intValue()))
                .hasPathSatisfying("$[2].deleted", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$[0].completed", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$[0].completedAt", v -> assertThat(v).isNull());

        // 전날에는 영어(아직 안 만듦)가 빠지고 전날 지운 과목이 들어온다
        assertThat(dayList(day.minusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[*].name", v -> assertThat(v).asList().containsExactly("수학", "그날 지움", "전날 지움"));
    }

    @Test
    void 과목_시간은_05시_창으로_잘려_그날과_다음_날에_나뉜다() {
        Long math = insertSubject(userId, "수학", dayStart.minusSeconds(86_400), null);
        // 그날 안 12:00~13:00 KST: 통째로 그날
        submitSession(dayStart.plusSeconds(7 * 3600), dayStart.plusSeconds(8 * 3600), 3600, "[]", math);
        // 다음 날 04:00~06:00 KST: 05:00에 걸친다. PAUSE 05:10~05:20은 다음 날 몫에서만 빠진다
        Instant crossStart = dayEnd.minusSeconds(3600);
        String pause = """
                [{"status": "PAUSE", "startedAt": "%s", "endedAt": "%s"}]""".formatted(dayEnd.plusSeconds(600), dayEnd.plusSeconds(1200));
        submitSession(crossStart, dayEnd.plusSeconds(3600), 6600, pause, math); // 총 공부는 일시정지를 뺀 값이어야 검증을 지난다

        assertThat(dayList(day))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(7200))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(7200));
        assertThat(dayList(day.plusDays(1)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(3000));
    }

    @Test
    void 완료_표시는_그_날짜에만_남고_다시_보내도_한_건이며_취소하면_사라진다() {
        Long math = insertSubject(userId, "수학", dayStart.minusSeconds(86_400), null);

        assertThat(complete(math, day)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(complete(math, day)).hasStatus(HttpStatus.NO_CONTENT);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_completion WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isEqualTo(1);
        assertThat(dayList(day))
                .bodyJson()
                .hasPathSatisfying("$[0].completed", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$[0].completedAt", v -> assertThat(v).isNotNull());
        assertThat(dayList(day.plusDays(1)))
                .bodyJson()
                .hasPathSatisfying("$[0].completed", v -> assertThat(v).isEqualTo(false));

        assertThat(uncomplete(math, day)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(uncomplete(math, day)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(dayList(day))
                .bodyJson()
                .hasPathSatisfying("$[0].completed", v -> assertThat(v).isEqualTo(false));
    }

    @Test
    void 지운_과목도_그날_있었으면_완료할_수_있지만_미래_날짜와_그날_없던_과목과_남의_과목은_거절한다() {
        Long gone = insertSubject(userId, "그날 지움", dayStart.minusSeconds(86_400), dayStart.plusSeconds(3600));
        Long later = insertSubject(userId, "다음날 과목", dayEnd, null);
        Long others = insertSubject(insertOtherUser(), "남의 과목", dayStart.minusSeconds(86_400), null);

        assertThat(complete(gone, day)).hasStatus(HttpStatus.NO_CONTENT);
        Long math = insertSubject(userId, "수학", dayStart.minusSeconds(86_400), null); // 살아있어 미래 검사만 걸린다
        assertThat(complete(math, LocalDate.now(KST).plusDays(1))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(complete(later, day)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(complete(others, day)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(complete(gone, LocalDate.now(KST))).hasStatus(HttpStatus.BAD_REQUEST); // 오늘은 지운 뒤라 없던 과목
        assertThat(subjectApi(mvc.put().uri("/api/subjects/" + gone + "/completions/2026-13-01")))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private Long insertOtherUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "other-" + UUID.randomUUID());
    }
}
