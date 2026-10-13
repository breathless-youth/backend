package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

class RetentionAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    private static RetentionRow row(RetentionSection section, Window window) {
        return section.rows().stream()
                .filter(row -> row.window() == window)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 구간이_다_끝난_최근_28일치_가입자만_분모로_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(1)).studyOnDays(1).build(), // D1 = A
                user(2, A.minusDays(28)).build(),
                user(3, A).studyOnDays(1).build(), // D1이 아직 안 왔다 — 분모에서 빠진다
                user(4, A.minusDays(29)).studyOnDays(1).build())); // 28일 밖

        RetentionRow nextDay = row(section, Window.NEXT_DAY);
        assertThat(nextDay.current()).isEqualTo(Rate.of(1, 2));
        assertThat(nextDay.cohortFrom()).isEqualTo(A.minusDays(28));
        assertThat(nextDay.cohortTo()).isEqualTo(A.minusDays(1));
    }

    @Test
    void 이주차와_오주차는_7일_구간_중_하루라도_공부하면_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(13)).studyOnDays(10).build(),
                user(2, A.minusDays(20)).studyOnDays(6, 14).build(), // 6일 뒤·14일 뒤에만 — 안 센다
                user(3, A.minusDays(40)).studyOnDays(7).build(),
                user(4, A.minusDays(34)).studyOnDays(30).build(),
                user(5, A.minusDays(61)).studyOnDays(27, 35).build()));

        // 가입 A−34인 4번도 [A−40, A−13] 안이라 7~13일 뒤 분모에 든다
        assertThat(row(section, Window.WEEK_TWO).current()).isEqualTo(Rate.of(2, 4));
        assertThat(row(section, Window.WEEK_TWO).cohortFrom()).isEqualTo(A.minusDays(40));
        // 가입 A−40인 3번도 [A−61, A−34] 안이라 28~34일 뒤 분모에 든다
        assertThat(row(section, Window.WEEK_FIVE).current()).isEqualTo(Rate.of(1, 3));
        assertThat(row(section, Window.WEEK_FIVE).cohortFrom()).isEqualTo(A.minusDays(61));
    }

    @Test
    void 지난주_값은_기준일을_7일_당겨_같은_계산을_한다() {
        // A−7 기준 다음 날 코호트 = 가입 [A−35, A−8]
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(8)).studyOnDays(1).build(),
                user(2, A.minusDays(30)).build()));

        assertThat(row(section, Window.NEXT_DAY).lastWeek()).isEqualTo(Rate.of(1, 2));
    }

    @Test
    void 첫_주에_한_번도_공부_안_한_비율을_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(6)).build(),
                user(2, A.minusDays(10)).studyOnDays(6).build(),
                user(3, A.minusDays(33)).studyOnDays(7).build(), // 둘째 주에만 — 첫 주 미사용
                user(4, A.minusDays(5)).build())); // 첫 주가 안 끝났다

        assertThat(section.firstWeekNone()).isEqualTo(Rate.of(2, 3));
        assertThat(section.noneFrom()).isEqualTo(A.minusDays(33));
        assertThat(section.noneTo()).isEqualTo(A.minusDays(6));
    }
}
