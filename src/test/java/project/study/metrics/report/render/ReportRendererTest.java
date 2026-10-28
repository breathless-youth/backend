package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.slack.SlackMessage;

class ReportRendererTest {

    @Test
    void 요약_하나와_스레드_일곱_개를_정해진_순서로_만든다() {
        RenderedReport rendered = ReportRenderer.render(ReportSamples.report());

        assertThat(rendered.summary().title()).isEqualTo("📊 10/12(월) 일일 지표");
        assertThat(rendered.threads())
                .extracting(SlackMessage::title)
                .containsExactly(
                        "🕐 가입",
                        "📈 다시 와서 공부한 비율",
                        "👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나",
                        "🙋 첫 주에 3일 이상 공부한 2명",
                        "🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3",
                        "🧑‍🎓 어제 공부한 15명",
                        "🔔 첫 세션 5분 이상 유도 Push 알림 A/B 테스트");
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
                base.active(),
                base.experiment());

        assertThat(ReportRenderer.render(report).threads().getFirst().text()).isEqualTo("🕐 가입\n• 계산 실패 — Sentry 확인");
    }

    @Test
    void 긴_스레드는_Slack_블록_한도_아래로_여러_댓글에_나눠_담는다() {
        List<ActiveUserRow> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(new ActiveUserRow(i, "닉네임이조금긴유저" + i, 3, 2, 3600, true, 4, "공무원(7급 합격을 목표로)", 5));
        }
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(),
                base.signup(),
                base.retention(),
                base.segment(),
                base.roster(),
                base.explorer(),
                SectionResult.of(new ActiveUsersSection(100, Map.of(Stage.NEW, rows), 0)),
                base.experiment());

        List<SlackMessage> threads = ReportRenderer.render(report).threads();

        assertThat(threads)
                .hasSizeGreaterThan(6)
                .allSatisfy(thread -> assertThat(thread.text().length()).isLessThanOrEqualTo(ReportRenderer.MAX_CHARS));
        List<SlackMessage> active = threads.subList(5, threads.size() - 1);
        assertThat(active)
                .extracting(SlackMessage::title)
                .startsWith("🧑‍🎓 어제 공부한 100명")
                .contains("🧑‍🎓 어제 공부한 100명 (이어서)");
        assertThat(active.getLast().text()).endsWith("• 앱만 열고 공부 안 함 = 0명");
        assertThat(threads.getLast().title()).isEqualTo("🔔 첫 세션 5분 이상 유도 Push 알림 A/B 테스트");
    }
}
