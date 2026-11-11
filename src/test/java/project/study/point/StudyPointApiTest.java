package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
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
import project.study.config.ApiVersionConfig;
import project.study.point.service.StudyPointService;

/** 세션 제출 → 공부 포인트 지급 → 잔액·결과 조회. 날짜는 서울 기준으로 한 날짜에 들어가게 골랐다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyPointApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    StudyPointService studyPointService;

    @Test
    void 측정한_70분은_20P를_받고_다시_제출해도_한_번만_받는다() {
        long user = fixture.member();

        submit(user, "2026-01-10T01:00:00Z", 4200);
        submit(user, "2026-01-10T01:00:00Z", 4200);

        assertThat(balance(user)).isEqualTo(20);
        assertThat(reward(user, "2026-01-10T01:00:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(20))
                .hasPathSatisfying("$.statDate", v -> assertThat(v).isEqualTo("2026-01-10"))
                .hasPathSatisfying("$.recognizedFocusSec", v -> assertThat(v).isEqualTo(4200))
                .hasPathSatisfying("$.dailyCapFocusSec", v -> assertThat(v).isEqualTo(43200))
                .hasPathSatisfying("$.nextMascot.code", v -> assertThat(v).isEqualTo("PLANNER"))
                .hasPathSatisfying(
                        "$.nextMascot.remainingFocusSec", v -> assertThat(v).isEqualTo(18000 - 4200));
    }

    @Test
    void 직접_입력한_기록은_포인트로_인정하지_않는다() {
        long user = fixture.member();
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec, source)
                VALUES (:u, DATE '2026-01-10', TIMESTAMPTZ '2026-01-10T05:00:00Z', TIMESTAMPTZ '2026-01-10T07:00:00Z', 7200, 7200, 'MANUAL')""").param("u", user).update();

        submit(user, "2026-01-10T01:00:00Z", 1800);

        assertThat(balance(user)).isEqualTo(10);
        assertThat(reward(user, "2026-01-10T01:00:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.recognizedFocusSec", v -> assertThat(v).isEqualTo(1800));
    }

    @Test
    void 자동_확정본이_받은_뒤_더_긴_최종_제출이_오면_차액만_더_받는다() {
        long user = fixture.member();
        Instant start = Instant.parse("2026-01-10T01:00:00Z");
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source, auto_finalized)
                VALUES (:u, DATE '2026-01-10', :s, :s, :e, 3600, 3600, 'MEASURED', true)""")
                .param("u", user)
                .param("s", Timestamp.from(start))
                .param("e", Timestamp.from(start.plusSeconds(3600)))
                .update();
        studyPointService.grantForSubmission(user, start);
        assertThat(balance(user)).isEqualTo(20);

        submit(user, "2026-01-10T01:00:00Z", 5400);

        assertThat(balance(user)).isEqualTo(30);
        assertThat(reward(user, "2026-01-10T01:00:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(30));
    }

    @Test
    void 삼십분에_못_미친_순공은_같은_날_다음_세션과_합쳐_받는다() {
        long user = fixture.member();

        submit(user, "2026-01-10T01:00:00Z", 1200);
        assertThat(balance(user)).isZero();

        submit(user, "2026-01-10T03:00:00Z", 1200);
        assertThat(balance(user)).isEqualTo(10);
        assertThat(reward(user, "2026-01-10T03:00:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(10));
    }

    @Test
    void 하루_12시간을_넘는_순공은_포인트로_인정하지_않는다() {
        long user = fixture.member();

        submit(user, "2026-01-10T00:00:00Z", 13 * 3600);

        assertThat(balance(user)).isEqualTo(240);
        assertThat(reward(user, "2026-01-10T00:00:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.recognizedFocusSec", v -> assertThat(v).isEqualTo(43200));
    }

    @Test
    void 시작_날짜보다_앞선_공부는_다시_제출해도_포인트를_받지_않는다() {
        long user = fixture.member();

        submit(user, "2025-12-20T01:00:00Z", 4200);
        submit(user, "2025-12-20T01:00:00Z", 4200);

        assertThat(balance(user)).isZero();
    }

    @Test
    void 자동_확정본이_받은_뒤_최종_제출이_다른_날짜로_바뀌어도_두_번_받지_않는다() {
        long user = fixture.member();
        Instant start = Instant.parse("2026-01-10T01:00:00Z");
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source, auto_finalized)
                VALUES (:u, DATE '2026-01-10', :s, :s, :e, 3600, 3600, 'MEASURED', true)""")
                .param("u", user)
                .param("s", Timestamp.from(start))
                .param("e", Timestamp.from(start.plusSeconds(3600)))
                .update();
        studyPointService.grantForSubmission(user, start);
        assertThat(balance(user)).isEqualTo(20);

        // 같은 세션의 최종 제출이 호놀룰루 시간대로 오면 날짜가 1월 9일로 바뀐다
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startedAt":"2026-01-10T01:00:00Z","endedAt":"2026-01-10T02:00:00Z","studySec":3600,
                                 "focusSec":3600,"events":[],"timeZone":"Pacific/Honolulu"}""")
                        .exchange())
                .hasStatus(HttpStatus.CREATED);

        assertThat(balance(user)).isEqualTo(20);
        assertThat(grantedOn(user, "2026-01-09")).isEqualTo(20);
        assertThat(grantedOn(user, "2026-01-10")).isZero();
        // 날짜를 옮긴 줄은 포인트 기록에 나오지 않는다
        assertThat(get(user, "/api/points/history"))
                .bodyJson()
                .hasPathSatisfying("$.items.length()", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 최종_제출이_한_날짜를_두_날짜로_쪼개도_더_받지_않는다() {
        long user = fixture.member();
        // 서울 23:30~00:30의 60분을 UTC 날짜(1월 10일) 하나로 자동 확정해 20P를 받은 상태
        Instant start = Instant.parse("2026-01-10T14:30:00Z");
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source, auto_finalized)
                VALUES (:u, DATE '2026-01-10', :s, :s, :e, 3600, 3600, 'MEASURED', true)""")
                .param("u", user)
                .param("s", Timestamp.from(start))
                .param("e", Timestamp.from(start.plusSeconds(3600)))
                .update();
        studyPointService.grantForSubmission(user, start);
        assertThat(balance(user)).isEqualTo(20);

        // 최종 제출이 서울 시간대로 오면 1월 10일 30분, 11일 30분으로 나뉜다
        submit(user, "2026-01-10T14:30:00Z", 3600);

        assertThat(balance(user)).isEqualTo(20);
        assertThat(grantedOn(user, "2026-01-10")).isEqualTo(10);
        assertThat(grantedOn(user, "2026-01-11")).isEqualTo(10);
        assertThat(reward(user, "2026-01-10T14:30:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(20));
    }

    @Test
    void 옛_날짜의_포인트가_다른_제출_이름으로_들어가_있어도_날짜가_바뀐_최종_제출이_더_받지_않는다() {
        long user = fixture.member();
        // A: 20분이 1월 10일로 자동 확정됐다(30분 미만이라 아직 0P)
        Instant startA = Instant.parse("2026-01-10T01:00:00Z");
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source, auto_finalized)
                VALUES (:u, DATE '2026-01-10', :s, :s, :e, 1200, 1200, 'MEASURED', true)""")
                .param("u", user)
                .param("s", Timestamp.from(startA))
                .param("e", Timestamp.from(startA.plusSeconds(1200)))
                .update();
        studyPointService.grantForSubmission(user, startA);
        // B: 같은 날 10분 — A와 합쳐 30분이 되어 B 이름으로 10P를 받는다
        submit(user, "2026-01-10T05:00:00Z", 600);
        assertThat(balance(user)).isEqualTo(10);

        // A의 최종 제출이 30분, 호놀룰루 시간대(1월 9일)로 온다
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startedAt":"2026-01-10T01:00:00Z","endedAt":"2026-01-10T01:30:00Z","studySec":1800,
                                 "focusSec":1800,"events":[],"timeZone":"Pacific/Honolulu"}""")
                        .exchange())
                .hasStatus(HttpStatus.CREATED);

        // 지금 기록은 1월 9일 30분, 10일 10분 — 받아야 할 총액은 10P다
        assertThat(balance(user)).isEqualTo(10);
        assertThat(grantedOn(user, "2026-01-09")).isEqualTo(10);
        assertThat(grantedOn(user, "2026-01-10")).isZero();
    }

    @Test
    void 시작_시각에_나노초가_있어도_자동_확정과_최종_제출이_같은_제출로_묶인다() {
        long user = fixture.member();
        Instant start = Instant.parse("2026-01-10T01:00:00.123456789Z");

        submit(user, start.toString(), 3600);
        studyPointService.grantForSubmission(user, start);

        assertThat(balance(user)).isEqualTo(20);
        assertThat(reward(user, start.toString()))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(20));
    }

    @Test
    void 자정을_넘는_세션은_날짜별로_따로_계산한다() {
        long user = fixture.member();

        // 서울 23:30~00:40 — 앞 날짜 30분, 뒤 날짜 40분
        submit(user, "2026-01-10T14:30:00Z", 4200);

        assertThat(balance(user)).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM point_ledger WHERE user_id = :u")
                        .param("u", user)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(reward(user, "2026-01-10T14:30:00Z"))
                .bodyJson()
                .hasPathSatisfying("$.earnedPoints", v -> assertThat(v).isEqualTo(20))
                .hasPathSatisfying("$.statDate", v -> assertThat(v).isEqualTo("2026-01-11"));
    }

    @Test
    void 비회원은_포인트를_받지_못하고_조회도_거절된다() {
        long guest = fixture.guest();

        submit(guest, "2026-01-10T01:00:00Z", 4200);

        assertThat(jdbc.sql("SELECT count(*) FROM point_ledger WHERE user_id = :u")
                        .param("u", guest)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(get(guest, "/api/points"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("MEMBER_ONLY"));
    }

    @Test
    void 토큰이_없으면_거절한다() {
        assertThat(mvc.get()
                        .uri("/api/points")
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private void submit(long user, String startedAt, int focusSec) {
        Instant start = Instant.parse(startedAt);
        String body = """
                {"startedAt":"%s","endedAt":"%s","studySec":%d,"focusSec":%d,"events":[],"timeZone":"Asia/Seoul"}""".formatted(start, start.plusSeconds(focusSec), focusSec, focusSec);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private int grantedOn(long user, String date) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM point_ledger
                        WHERE user_id = :u AND stat_date = CAST(:d AS date)""")
                .param("u", user)
                .param("d", date)
                .query(Integer.class)
                .single();
    }

    private int balance(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    private MvcTestResult reward(long user, String startedAt) {
        return get(user, "/api/points/study-reward?startedAt=" + startedAt);
    }

    private MvcTestResult get(long user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }
}
