package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 포인트 받기 탭과 보상 받기. 사용자 시간대는 기본값(서울)이라 오늘은 서울 날짜다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PointEarnApiTest {

    /** ranking_record는 (판, 기간 시작, 순위)가 전체에서 유일하다 — 테스트끼리 겹치지 않게 먼 미래 날짜를 하나씩 쓴다. */
    private static final AtomicInteger PERIOD = new AtomicInteger();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    @Test
    void 출석은_하루_한_번만_지급하고_다시_요청하면_지급_없이_잔액을_준다() {
        long user = fixture.member();

        assertThat(get(user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.date", v -> assertThat(v).isEqualTo(today()))
                .hasPathSatisfying("$.todayEarned", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.dailyMax", v -> assertThat(v).isEqualTo(240 + 10 + 50))
                .hasPathSatisfying("$.attendance.claimed", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.study.maxPoints", v -> assertThat(v).isEqualTo(240))
                .hasPathSatisfying("$.medals.length()", v -> assertThat(v).isEqualTo(0));

        assertThat(claim(user, "attendance"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.granted", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.points", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(10));
        assertThat(claim(user, "attendance"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.granted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.points", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(10));

        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.todayEarned", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.attendance.claimed", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 하루_목표는_측정한_순공이_목표를_채워야_받는다() {
        long user = fixture.member();
        setDefaultGoal(user, 3600);
        fixture.measuredSession(user, today(), 0, 1800);

        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.dailyGoal.goalSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying(
                        "$.dailyGoal.remainingSec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying("$.dailyGoal.achieved", v -> assertThat(v).isEqualTo(false));
        assertCode(claim(user, "daily-goal"), HttpStatus.CONFLICT, "REWARD_NOT_AVAILABLE");

        fixture.measuredSession(user, today(), 1, 1800);

        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.dailyGoal.achieved", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.dailyGoal.claimed", v -> assertThat(v).isEqualTo(false));
        assertThat(claim(user, "daily-goal"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.points", v -> assertThat(v).isEqualTo(50));
        assertThat(claim(user, "daily-goal"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.granted", v -> assertThat(v).isEqualTo(false));
        // 받은 뒤 목표를 올려도 다시 요청하면 에러가 아니라 이미 받았다고 답한다
        jdbc.sql("UPDATE planner_default_goal SET goal_sec = 7200 WHERE user_id = :u")
                .param("u", user)
                .update();
        assertThat(claim(user, "daily-goal"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.granted", v -> assertThat(v).isEqualTo(false));
        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.dailyGoal.claimed", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void 목표가_없거나_최소_목표보다_짧으면_채워도_받지_못한다() {
        long noGoal = fixture.member();
        fixture.measuredSession(noGoal, today(), 0, 7200);
        assertCode(claim(noGoal, "daily-goal"), HttpStatus.CONFLICT, "REWARD_NOT_AVAILABLE");

        long shortGoal = fixture.member();
        setDefaultGoal(shortGoal, 1800);
        fixture.measuredSession(shortGoal, today(), 0, 7200);
        assertThat(get(shortGoal))
                .bodyJson()
                .hasPathSatisfying("$.dailyGoal.minGoalSec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$.dailyGoal.achieved", v -> assertThat(v).isEqualTo(false));
        assertCode(claim(shortGoal, "daily-goal"), HttpStatus.CONFLICT, "REWARD_NOT_AVAILABLE");
    }

    @Test
    void 메달은_내_것만_한_번_받고_받은_메달은_목록에서_빠진다() {
        long user = fixture.member();
        long other = fixture.member();
        long weeklySecond = medal(user, "FOCUS_TIME:WEEKLY", futurePeriod(), 2);
        long othersMedal = medal(other, "FOCUS_TIME:DAILY", futurePeriod(), 1);
        long beforeStart =
                medal(user, "FOCUS_TIME:DAILY", LocalDate.of(2025, 12, 1).plusDays(PERIOD.incrementAndGet()), 1);

        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.medals.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.medals[0].recordId", v -> assertThat(v).isEqualTo((int) weeklySecond))
                .hasPathSatisfying("$.medals[0].period", v -> assertThat(v).isEqualTo("WEEKLY"))
                .hasPathSatisfying("$.medals[0].points", v -> assertThat(v).isEqualTo(150));

        assertCode(claim(user, "medals/" + othersMedal), HttpStatus.NOT_FOUND, "MEDAL_NOT_FOUND");
        assertCode(claim(user, "medals/" + beforeStart), HttpStatus.NOT_FOUND, "MEDAL_NOT_FOUND");
        assertThat(claim(user, "medals/" + weeklySecond))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.points", v -> assertThat(v).isEqualTo(150))
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(150));
        assertThat(claim(user, "medals/" + weeklySecond))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.granted", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(150));

        assertThat(get(user))
                .bodyJson()
                .hasPathSatisfying("$.medals.length()", v -> assertThat(v).isEqualTo(0))
                // 메달은 오늘 받은 합계에 들지 않는다
                .hasPathSatisfying("$.todayEarned", v -> assertThat(v).isEqualTo(0));
        assertThat(jdbc.sql("SELECT label FROM point_ledger WHERE user_id = :u AND reason = 'MEDAL'")
                        .param("u", user)
                        .query(String.class)
                        .single())
                .isEqualTo("FOCUS_TIME:WEEKLY/2");
    }

    @Test
    void 비회원은_받기_탭과_보상을_쓰지_못한다() {
        long guest = fixture.guest();

        assertCode(get(guest), HttpStatus.FORBIDDEN, "MEMBER_ONLY");
        assertCode(claim(guest, "attendance"), HttpStatus.FORBIDDEN, "MEMBER_ONLY");
    }

    private static LocalDate futurePeriod() {
        return LocalDate.of(2200, 1, 1).plusDays(PERIOD.incrementAndGet());
    }

    private long medal(long user, String boardKey, LocalDate periodStart, int rank) {
        return jdbc.sql("""
                        INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                        VALUES (:u, :b, :p, now(), :r, 3600) RETURNING id""")
                .param("u", user)
                .param("b", boardKey)
                .param("p", periodStart)
                .param("r", rank)
                .query(Long.class)
                .single();
    }

    private void setDefaultGoal(long user, int goalSec) {
        jdbc.sql("INSERT INTO planner_default_goal (user_id, goal_sec) VALUES (:u, :g)")
                .param("u", user)
                .param("g", goalSec)
                .update();
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    private MvcTestResult get(long user) {
        return mvc.get()
                .uri("/api/points/earn")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }

    private MvcTestResult claim(long user, String reward) {
        return mvc.post()
                .uri("/api/points/rewards/" + reward + "/claim")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }
}
