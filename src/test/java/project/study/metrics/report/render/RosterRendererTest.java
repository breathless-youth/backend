package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

class RosterRendererTest {

    @Test
    void 카드는_누구_공부_한_일_습관_네_줄이다() {
        assertThat(RosterRenderer.card(ReportSamples.bearCard(), ReportSamples.A))
                .containsExactly(
                        "🆕 공부하는곰(#418) · 9/29(화) 22시 가입 · 공무원(7급 합격) · iOS · 유입: 광고(instagram)",
                        "   공부: 첫 주 5일·11시간 20분(최장 연속 4일) → 둘째 주 4일·8시간 5분 → 마지막 공부 어제 · 지금 연속 3일",
                        "   첫 주에 한 일: 소셜 방 7회 · 과목 4개 · 할 일 완료 12개 · D-day · 간단 모드 · 가로 화면 · 백색소음 · 랭킹 · 앱 실행 6일",
                        "   습관: 세션 평균 48분 · 주로 21~24시 · 소셜 순공 62% · 일시정지 세션당 0.5회 · 딴짓 1시간당 1.2회");
    }

    @Test
    void 떠난_사람은_둘째_주_0일과_첫_주_마지막_공부를_쓴다() {
        assertThat(RosterRenderer.card(ReportSamples.leftCard(), ReportSamples.A)
                        .get(1))
                .isEqualTo("   공부: 첫 주 3일·1시간 50분(최장 연속 3일) → 둘째 주 0일 → 마지막 공부 8일 전(첫 주)");
    }

    @Test
    void 명단은_두_묶음과_빠진_사람_수를_쓴다() {
        RosterSection base = ReportSamples.roster();
        RosterSection section = new RosterSection(
                base.anchor(),
                base.cohortFrom(),
                base.cohortTo(),
                new RosterGroup(2, List.of(ReportSamples.bearCard()), LocalDate.of(2026, 9, 2)),
                base.dropped());

        assertThat(RosterRenderer.render(section).lines())
                .contains(
                        "🙋 첫 주에 3일 이상 공부한 3명 (가입 9/2~9/29)",
                        "🆕 = 어제로 둘째 주가 끝나 새로 들어온 사람(9/29 가입) · \"한 일\"은 첫 주 기준",
                        "✅ 둘째 주에도 공부한 2명",
                        "⋯ 외 1명 (가입 9/2 이전)",
                        "❌ 둘째 주에 안 온 1명");
    }

    @Test
    void 명단이_비면_한_줄로_쓴다() {
        RosterSection empty = new RosterSection(
                ReportSamples.A,
                ReportSamples.COHORT_FROM,
                ReportSamples.COHORT_TO,
                new RosterGroup(0, List.of(), null),
                new RosterGroup(0, List.of(), null));

        assertThat(RosterRenderer.render(empty)).isEqualTo("🙋 첫 주에 3일 이상 공부한 사람 없음 (가입 9/2~9/29)");
    }
}
