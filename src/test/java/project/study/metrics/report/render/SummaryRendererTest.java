package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

class SummaryRendererTest {

    @Test
    void 요약은_용어_없이_풀어_쓴_다섯_줄이다() {
        assertThat(SummaryRenderer.render(ReportSamples.report())).isEqualTo("""
                        📊 10/12(월) 일일 지표
                        • 가입 38명 · 가장 많이 가입한 시간 21~24시
                        • 다시 와서 공부한 비율: 가입 다음 날 10% · 7~13일 뒤 6% · 28~34일 뒤 3%  (지난주 11% · 5% · 3%)
                        • 첫 주에 3일 이상 공부한 14명 중 8명(57%)이 둘째 주에도 공부 — 1~2일 공부한 사람은 13%, 안 한 사람은 1% (명단은 스레드)
                        • 남는 사람과 가장 관련 깊은 행동: 첫 주에 소셜 방 3번 이상 참여 (연관 강함, 3일째 1위)
                        • 어제 공부한 15명: 새로 온 사람 6 (가입 첫 주) · 꾸준한 사람 5 (최근 7일 중 3일+) · 돌아온 사람 2 (2주 쉬다 옴) · 가끔 오는 사람 2 (최근 7일 중 1~2일)""");
    }

    @Test
    void 계산에_실패한_섹션은_그_줄만_계산_실패로_쓴다() {
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(),
                base.signup(),
                SectionResult.failed(),
                base.segment(),
                base.roster(),
                base.explorer(),
                base.active());

        assertThat(SummaryRenderer.render(report))
                .contains("• 다시 와서 공부한 비율: 계산 실패")
                .contains("• 가입 38명");
    }
}
