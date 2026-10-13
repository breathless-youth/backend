package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

class ReportRendererTest {

    @Test
    void 요약_하나와_스레드_여섯_개를_정해진_순서로_만든다() {
        RenderedReport rendered = ReportRenderer.render(ReportSamples.report());

        assertThat(rendered.summary()).startsWith("📊 10/12(월) 일일 지표");
        assertThat(rendered.threads())
                .hasSize(6)
                .satisfiesExactly(
                        text -> assertThat(text).startsWith("🕐 가입"),
                        text -> assertThat(text).startsWith("📈 다시 와서 공부한 비율"),
                        text -> assertThat(text).startsWith("👥 첫 주에 며칠 공부했나"),
                        text -> assertThat(text).startsWith("🙋 첫 주에 3일 이상 공부한"),
                        text -> assertThat(text).startsWith("🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3"),
                        text -> assertThat(text).startsWith("🧑‍🎓 어제 공부한"));
    }

    @Test
    void 계산에_실패한_섹션의_스레드는_계산_실패로_쓴다() {
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(),
                SectionResult.failed(),
                base.retention(),
                base.segment(),
                base.roster(),
                base.explorer(),
                base.active());

        assertThat(ReportRenderer.render(report).threads().getFirst()).isEqualTo("🕐 가입\n계산 실패 — Sentry 확인");
    }

    @Test
    void 사만_자를_넘는_메시지는_잘라_알린다() {
        String capped = ReportRenderer.cap("가".repeat(50_000));

        assertThat(capped).hasSize(ReportRenderer.MAX_CHARS).endsWith("…(길이 상한 40,000자로 잘림)");
        assertThat(ReportRenderer.cap("짧은 글")).isEqualTo("짧은 글");
    }
}
