package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.rule.TwoByTwo;

class SegmentRendererTest {

    @Test
    void 세_묶음_비교와_주로_한_행동_어떤_사람인가를_들여쓴_목록으로_쓴다() {
        assertThat(SegmentRenderer.render(ReportSamples.segment()).text()).isEqualTo("""
                        👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나
                        • 대상 = 가입 9/2~9/29 *252*명
                            • 첫 주 = 가입일 포함 7일 · 둘째 주 = 가입 7~13일 뒤
                        • 둘째 주에도 공부한 비율
                            • 3일 이상 14명(6%) = *57%* (8/14)
                            • 1~2일 46명(18%) = *13%* (6/46)
                            • 0일 192명(76%) = *1%* (2/192)
                        > 3일 이상 vs 나머지 = 57% vs 3% (+54%p) · 연관 강함 (0.51)
                        • 3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 14명 / 나머지 238명)
                            1. 소셜 방 3번 이상 참여 = *57%* / 1%
                            2. D-day 설정 = *50%* / 10%
                            3. 프로필(분야·목표) 입력 = *93%* / 74% · 지금 기준(입력 시점은 기록 없음)
                        • 어떤 사람인가 (3일 이상 / 나머지)
                            • 분야 = 공무원 36% / 21% · 미입력 7% / 29%
                            • 광고 유입 = 29% / 41%""");
    }

    @Test
    void 첫_주_3일_이상이_5명_미만이면_비교를_생략한다고_쓴다() {
        SegmentSection base = ReportSamples.segment();
        SegmentSection section = new SegmentSection(
                base.cohortFrom(),
                base.cohortTo(),
                20,
                base.groups(),
                new TwoByTwo(2, 1, 1, 16),
                false,
                true,
                List.of(),
                base.profile(),
                List.of());

        assertThat(SegmentRenderer.render(section).text())
                .endsWith("• 3일 이상이 3명이라 행동·특징 비교는 생략 (5명 미만, 표본 부족)")
                .doesNotContain("어떤 사람인가");
    }

    @Test
    void 주로_한_행동이_없으면_그렇게_쓰고_프로필은_1번이다() {
        SegmentSection base = ReportSamples.segment();
        SegmentSection section = new SegmentSection(
                base.cohortFrom(),
                base.cohortTo(),
                base.cohortSize(),
                base.groups(),
                base.table(),
                true,
                false,
                List.of(),
                base.profile(),
                base.who());

        assertThat(SegmentRenderer.render(section).text().lines())
                .containsSubsequence(
                        "• 3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 14명 / 나머지 238명) · Amplitude 미수집 기간이라 앱 행동은 뺌",
                        "    • 3일 이상의 30% 이상이 한 행동 없음",
                        "    1. 프로필(분야·목표) 입력 = *93%* / 74% · 지금 기준(입력 시점은 기록 없음)");
    }
}
