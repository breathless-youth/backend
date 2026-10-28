package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.analyzer.ExperimentSection.ArmStats;
import project.study.metrics.report.fact.ExperimentFact;

class ExperimentAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 11, 20);
    private static final Instant ASSIGNED = Kst.startOf(A.minusDays(10));
    private static final Instant BEFORE_CUTOFF = Kst.startOf(A).plusSeconds(3600);
    private static final Instant AFTER_CUTOFF = Kst.startOf(A.plusDays(1)).plusSeconds(60);

    private static ExperimentFact fact(
            long userId, String arm, Instant firstAt, Integer focus, Boolean day7, boolean hasToken, boolean on) {
        return new ExperimentFact(userId, arm, ASSIGNED, firstAt, focus, day7, hasToken, on);
    }

    private static ExperimentSection analyze(List<ExperimentFact> facts) {
        return ExperimentAnalyzer.analyze(ReportDataFixture.data(A, true, facts));
    }

    @Test
    void 집단별로_첫_세션과_D7과_알림_꺼짐을_센다() {
        ExperimentSection section = analyze(List.of(
                fact(1, "PUSH", BEFORE_CUTOFF, 300, true, true, true),
                fact(2, "PUSH", BEFORE_CUTOFF, 299, false, true, false),
                fact(3, "PUSH", null, null, null, false, false),
                fact(4, "CONTROL", BEFORE_CUTOFF, 600, true, true, true)));

        assertThat(section.push())
                .isEqualTo(new ArmStats(3, Rate.of(2, 3), Rate.of(1, 3), Rate.of(1, 2), Rate.of(1, 2), 1));
        assertThat(section.control())
                .isEqualTo(new ArmStats(1, Rate.of(1, 1), Rate.of(1, 1), Rate.of(1, 1), Rate.of(0, 1), 0));
        assertThat(section.judgedPerArm()).isEqualTo(1);
    }

    @Test
    void 기준일이_끝난_뒤의_첫_세션과_배정은_다음_리포트로_미룬다() {
        ExperimentSection section = analyze(List.of(
                fact(1, "PUSH", AFTER_CUTOFF, 900, null, true, true),
                new ExperimentFact(2, "CONTROL", AFTER_CUTOFF, null, null, null, true, true)));

        assertThat(section.push().assigned()).isEqualTo(1);
        assertThat(section.push().started()).isEqualTo(Rate.of(0, 1));
        assertThat(section.push().firstFiveMinutes()).isEqualTo(Rate.of(0, 1));
        assertThat(section.control().assigned()).isZero();
    }

    @Test
    void 실험_사실을_읽지_못했으면_이_섹션만_실패한다() {
        ReportData data = new ReportData(A, List.of(), java.util.Set.of(), java.util.Set.of(), null);

        assertThatThrownBy(() -> ExperimentAnalyzer.analyze(data)).isInstanceOf(IllegalStateException.class);
    }
}
