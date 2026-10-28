package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyReportTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Test
    void 섹션_여섯_개를_모두_계산한다() {
        DailyReport report = DailyReport.analyze(
                ReportDataFixture.data(A, true, user(1, A).studyOnDay(0, 30).build()));

        assertThat(report.anchor()).isEqualTo(A);
        assertThat(report.signup().value()).isPresent();
        assertThat(report.retention().value()).isPresent();
        assertThat(report.segment().value()).isPresent();
        assertThat(report.roster().value()).isPresent();
        assertThat(report.explorer().value()).isPresent();
        assertThat(report.active().value())
                .hasValueSatisfying(active -> assertThat(active.total()).isEqualTo(1));
    }

    @Test
    void 한_섹션이_예외를_던지면_그_섹션만_실패로_남는다() {
        SectionResult<String> failed = SectionResult.compute("테스트", () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(failed.value()).isEmpty();
        assertThat(SectionResult.compute("테스트", () -> "ok").value()).contains("ok");
    }

    @Test
    void 빈_데이터도_모든_섹션이_계산_실패_없이_없음으로_나온다() {
        for (boolean amplitude : new boolean[] {true, false}) {
            var rendered = project.study.metrics.report.render.ReportRenderer.render(
                    DailyReport.analyze(ReportDataFixture.data(A, amplitude)));

            assertThat(rendered.summary().text()).doesNotContain("계산 실패", "NaN");
            assertThat(rendered.threads())
                    .hasSize(7)
                    .allSatisfy(thread -> assertThat(thread.text()).doesNotContain("계산 실패", "NaN"));
        }
    }
}
