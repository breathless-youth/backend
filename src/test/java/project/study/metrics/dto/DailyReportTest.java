package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class DailyReportTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 8);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant kst(int hour, int minute) {
        return LocalDateTime.of(2026, 8, 8, hour, minute).atZone(KST).toInstant();
    }

    @Test
    void 신규_가입은_수와_userId_가입시각을_함께_적는다() {
        DailyReport report = new DailyReport(
                DATE, 53, List.of(new NewUser(51L, kst(9, 31)), new NewUser(52L, kst(21, 5))), List.of(), 6);

        assertThat(report.toSlackMessage())
                .contains("신규 가입: 2명")
                .contains("#51(09:31)")
                .contains("#52(21:05)");
    }

    @Test
    void 가입시각은_KST로_표시된다() {
        // UTC 자정 직전 가입 = KST로는 같은 날 아침 — UTC 그대로 찍으면 시각이 틀린다
        Instant utcMidnightBefore = Instant.parse("2026-08-08T00:10:00Z"); // KST 09:10
        DailyReport report = new DailyReport(DATE, 1, List.of(new NewUser(1L, utcMidnightBefore)), List.of(), 0);

        assertThat(report.toSlackMessage()).contains("#1(09:10)");
    }

    @Test
    void 신규_가입이_없으면_목록_없이_0명만_적는다() {
        DailyReport report = new DailyReport(DATE, 53, List.of(), List.of(), 0);

        assertThat(report.toSlackMessage()).contains("신규 가입: 0명").doesNotContain("신규 가입: 0명 —");
    }

    @Test
    void 신규_가입이_20명_초과면_상위_20명만_나열하고_나머지는_개수로_요약한다() {
        List<NewUser> newUsers = LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new NewUser(id, kst(10, 0)))
                .toList();
        DailyReport report = new DailyReport(DATE, 100, newUsers, List.of(), 0);

        String message = report.toSlackMessage();
        assertThat(message).contains("신규 가입: 25명").contains("#1(10:00)").contains("#20(10:00)");
        assertThat(message).contains("외 5명");
        assertThat(message).doesNotContain("#21(10:00)").doesNotContain("#25(10:00)");
    }

    @Test
    void 헤비유저가_있으면_수와_ID_인정일수를_함께_적는다() {
        DailyReport report =
                new DailyReport(DATE, 53, List.of(), List.of(new HeavyUser(14L, 7L), new HeavyUser(13L, 4L)), 6);

        assertThat(report.toSlackMessage())
                .contains("2026-08-08")
                .contains("총 가입: 53명")
                .contains("헤비유저: 2명")
                .contains("#14(7일)")
                .contains("#13(4일)")
                .contains("10분 이상 세션: 6건");
    }

    @Test
    void 헤비유저가_없으면_목록_대신_없음을_적는다() {
        DailyReport report = new DailyReport(DATE, 53, List.of(), List.of(), 0);

        assertThat(report.toSlackMessage()).contains("헤비유저: 0명").contains("없음");
    }

    @Test
    void 헤비유저가_20명_초과면_상위_20명만_나열하고_나머지는_개수로_요약한다() {
        List<HeavyUser> heavyUsers = LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new HeavyUser(id, 7L))
                .toList();
        DailyReport report = new DailyReport(DATE, 100, List.of(), heavyUsers, 0);

        String message = report.toSlackMessage();
        assertThat(message)
                .contains("헤비유저: 25명")
                .contains("#1(7일)")
                .contains("#20(7일)")
                .contains("외 5명");
        assertThat(message).doesNotContain("#21(7일)").doesNotContain("#25(7일)");
    }
}
