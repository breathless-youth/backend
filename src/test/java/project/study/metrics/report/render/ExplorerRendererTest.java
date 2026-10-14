package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.rule.Exposure;

class ExplorerRendererTest {

    @Test
    void 규칙은_번호_목록으로_쓰고_인원과_비교를_하위_항목으로_쓴다() {
        assertThat(ExplorerRenderer.render(ReportSamples.explorer()).text()).isEqualTo("""
                        🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3
                        • 대상 = 가입 9/2~9/29 252명 · 남은 사람 = 둘째 주에도 공부한 16명
                            • 연관 = 0.4 이상 강함 / 0.3~0.4 보통 / 0.2~0.3 약함

                        1. *첫 주에 소셜 방 3번 이상 참여* — 연관 강함 (0.53) · 3일째 1위
                            • 10명 (4%) · 남은 16명 중 7명이 했음
                            • 둘째 주에도 공부 = *70%* (7/10) vs 안 한 사람 4% (9/242)
                        2. *첫 주에 공부한 날 3일 이상* (고정 세그먼트) — 연관 강함 (0.51) · 3일째 상위권
                            • 14명 (6%) · 남은 16명 중 8명이 했음
                            • 둘째 주에도 공부 = *57%* (8/14) vs 안 한 사람 3% (8/238)

                        • 규칙 40개 중 통과 6개
                            • 제외 = 사람 부족 14 · 너무 흔함 2 · 상관 약함 15 · Amplitude 미수집 3
                        > ⚠️ 상관관계이지 인과는 아님. 실험으로 확인할 후보""");
    }

    @Test
    void 통과가_없고_고정_규칙이_빠지면_이유를_쓴다() {
        ExplorerSection section = new ExplorerSection(
                ReportSamples.COHORT_FROM,
                ReportSamples.COHORT_TO,
                12,
                1,
                List.of(),
                Map.of(Exposure.TOO_FEW, 40),
                Exposure.TOO_FEW);

        assertThat(ExplorerRenderer.render(section).text().lines())
                .containsSubsequence(
                        "• 오늘은 조건을 통과한 규칙 없음 (표본 부족)",
                        "• 규칙 40개 중 통과 0개",
                        "    • 제외 = 사람 부족 40 · 너무 흔함 0 · 상관 약함 0 · Amplitude 미수집 0",
                        "    • 첫 주 3일 이상(고정 세그먼트)은 사람 부족으로 빠짐");
    }
}
