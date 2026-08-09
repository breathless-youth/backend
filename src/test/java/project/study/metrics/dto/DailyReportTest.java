package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import project.study.studysession.dto.HeavyUser;

class DailyReportTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 8);

    @Test
    void 헤비유저가_있으면_수와_ID_인정일수를_함께_적는다() {
        DailyReport report = new DailyReport(DATE, 53, 4, List.of(new HeavyUser(14L, 7L), new HeavyUser(13L, 4L)), 6);

        assertThat(report.toSlackMessage())
                .contains("2026-08-08")
                .contains("총 가입: 53명")
                .contains("2026-08-08 신규: 4명")
                .contains("헤비유저: 2명")
                .contains("#14(7일)")
                .contains("#13(4일)")
                .contains("10분 이상 세션: 6건");
    }

    @Test
    void 헤비유저가_없으면_목록_대신_없음을_적는다() {
        DailyReport report = new DailyReport(DATE, 53, 0, List.of(), 0);

        assertThat(report.toSlackMessage()).contains("헤비유저: 0명").contains("없음");
    }

    @Test
    void reportDate가_어제가_아니어도_신규_라벨이_그_날짜를_따라간다() {
        // 수동 재발송·백필로 다른 anchor를 넣을 수 있으므로 "어제"로 고정된 문구는 거짓말이 될 수 있다
        LocalDate backfillDate = LocalDate.of(2026, 7, 1);
        DailyReport report = new DailyReport(backfillDate, 10, 1, List.of(), 0);

        assertThat(report.toSlackMessage()).contains("2026-07-01 신규: 1명").doesNotContain("어제");
    }

    @Test
    void 헤비유저가_20명_초과면_상위_20명만_나열하고_나머지는_개수로_요약한다() {
        List<HeavyUser> heavyUsers = LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new HeavyUser(id, 7L))
                .toList();
        DailyReport report = new DailyReport(DATE, 100, 0, heavyUsers, 0);

        String message = report.toSlackMessage();
        assertThat(message)
                .contains("헤비유저: 25명")
                .contains("#1(7일)")
                .contains("#20(7일)")
                .contains("외 5명");
        assertThat(message).doesNotContain("#21(7일)").doesNotContain("#25(7일)");
    }
}
