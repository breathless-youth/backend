package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class DailyReportTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 8);

    private static final RoomVsSingleFocus FOCUS = RoomVsSingleFocus.from(List.of(
            new QualifyingSession(1L, 4080, true), // 소셜 68분
            new QualifyingSession(2L, 3300, false))); // 싱글 55분
    private static final CohortFirstWeek COHORT = CohortFirstWeek.from(List.of(4L, 4L, 4L));
    private static final List<UserSessionDetail> DETAILS =
            UserSessionDetail.rank(List.of(new QualifyingSession(12L, 8520, true))); // #12 142분 소셜

    private DailyReport report(
            List<NewUser> newUsers,
            List<HeavyUser> heavyUsers,
            long qualifyingSessions,
            RoomVsSingleFocus focus,
            CohortFirstWeek cohort,
            List<UserSessionDetail> details) {
        return new DailyReport(DATE, 151, newUsers, heavyUsers, qualifyingSessions, focus, cohort, details);
    }

    @Test
    void 신규가입은_유저ID와_가입시각을_함께_적는다() {
        DailyReport report = report(
                List.of(new NewUser(176L, "13:59"), new NewUser(177L, "14:38")), List.of(), 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage())
                .contains("총 가입: 151명")
                .contains("신규 가입: 2명")
                .contains("#176(13:59)")
                .contains("#177(14:38)");
    }

    @Test
    void 신규가입이_없으면_수만_적고_목록은_생략한다() {
        DailyReport report = report(List.of(), List.of(), 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("신규 가입: 0명");
    }

    @Test
    void 소셜과_싱글_순공을_건수_평균_중앙값으로_적는다() {
        DailyReport report = report(List.of(), List.of(), 130, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("소셜: 1건(평균 68분, 중앙 68분)").contains("싱글: 1건(평균 55분, 중앙 55분)");
    }

    @Test
    void 코호트_첫주_평균공부일수를_소수로_적는다() {
        DailyReport report = report(List.of(), List.of(), 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("첫주 평균 공부일수: 4.0일 (코호트 3명)");
    }

    @Test
    void 코호트가_비면_집계없음으로_적는다() {
        DailyReport report = report(List.of(), List.of(), 0, FOCUS, CohortFirstWeek.from(List.of()), DETAILS);

        assertThat(report.toSlackMessage()).contains("첫주 평균 공부일수: 집계 없음 (코호트 0명)");
    }

    @Test
    void 세션상세는_유저별로_묶어_순공과_소셜여부를_적는다() {
        DailyReport report = report(List.of(), List.of(), 1, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("#12: 142분(소셜)");
    }

    @Test
    void 세션상세가_없으면_없음을_적는다() {
        DailyReport report = report(List.of(), List.of(), 0, FOCUS, COHORT, List.of());

        assertThat(report.toSlackMessage()).contains("10분 이상 세션 상세").contains("없음");
    }

    @Test
    void 세션상세가_20명_초과면_상위_20명만_적고_나머지는_개수로_요약한다() {
        List<QualifyingSession> many = LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new QualifyingSession(id, (int) (id * 100 + 600), false))
                .toList();
        DailyReport report = report(List.of(), List.of(), 25, FOCUS, COHORT, UserSessionDetail.rank(many));

        String message = report.toSlackMessage();
        assertThat(message).contains("총 25명 중 상위 20명, 외 5명");
    }

    @Test
    void 헤비유저가_없으면_없음을_적는다() {
        DailyReport report = report(List.of(), List.of(), 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("헤비유저: 0명").contains("없음");
    }

    @Test
    void 헤비유저는_전부_나열하되_극단적으로_많으면_Slack상한을_넘지_않는다() {
        List<HeavyUser> heavyUsers = LongStream.rangeClosed(1, 100_000)
                .mapToObj(id -> new HeavyUser(id, 7L))
                .toList();
        DailyReport report = report(List.of(), heavyUsers, 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage().length()).isLessThanOrEqualTo(40_000);
    }

    @Test
    void 한_유저의_세션이_폭증해도_Slack상한을_넘지_않는다() {
        // 유저 수는 20명으로 제한되지만 유저당 세션 수는 무제한이라, 한 줄이 폭증할 수 있다(codex P1)
        List<QualifyingSession> many = LongStream.rangeClosed(1, 20_000)
                .mapToObj(i -> new QualifyingSession(1L, 600 + (int) i, true))
                .toList();
        DailyReport report = report(List.of(), List.of(), many.size(), FOCUS, COHORT, UserSessionDetail.rank(many));

        assertThat(report.toSlackMessage().length()).isLessThanOrEqualTo(40_000);
    }

    @Test
    void reportDate가_어제가_아니어도_신규_문구에_어제라는_표현은_없다() {
        LocalDate backfillDate = LocalDate.of(2026, 7, 1);
        DailyReport report = new DailyReport(
                backfillDate, 10, List.of(new NewUser(1L, "09:00")), List.of(), 0, FOCUS, COHORT, DETAILS);

        assertThat(report.toSlackMessage()).contains("2026-07-01").doesNotContain("어제");
    }
}
