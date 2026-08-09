package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
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
                .contains("어제 신규: 4명")
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
}
