package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

class SummaryRendererTest {

    @Test
    void 요약은_가입과_리텐션_묶음을_들여쓴_목록으로_쓰고_핵심_숫자를_굵게_쓴다() {
        assertThat(SummaryRenderer.render(ReportSamples.report()).text()).isEqualTo("""
                        📊 10/12(월) 일일 지표
                        *가입*
                        • 가입자 수 = *38*명
                        • 가장 많이 가입한 시간 = *21~24시*
                        • 다시 와서 공부한 비율
                            • *10%* (가입 다음 날)
                            • *6%* (7~13일 뒤)
                            • *3%* (28~34일 뒤)
                        > 지난주 11% · 5% · 3%

                        *리텐션*
                        • 첫 주에 공부한 날별로 둘째 주에도 공부한 비율
                            • 3일 이상 = *57%* (8/14)
                            • 1~2일 = *13%* (6/46)
                            • 0일 = *1%* (2/192)
                        > 명단은 스레드 참고
                        • 어제 공부한 *15*명 구성
                            • New (가입 첫 주) = *6*
                            • Lovers (최근 7일 중 3일+) = *5*
                            • Comeback (1주 쉬다 옴) = *2*
                            • Sometimes (최근 7일 중 1~2일) = *2*
                        • 남는 사람과 가장 관련 깊은 행동 = *첫 주에 소셜 방 3번 이상 참여* (연관 강함, 3일째 1위)""");
    }

    @Test
    void 계산에_실패한_섹션은_그_항목만_계산_실패로_쓴다() {
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(),
                base.signup(),
                SectionResult.failed(),
                base.segment(),
                base.roster(),
                base.explorer(),
                base.active());

        assertThat(SummaryRenderer.render(report).text().lines())
                .contains("• 다시 와서 공부한 비율 = 계산 실패", "• 가입자 수 = *38*명")
                .doesNotContain("> 지난주 11% · 5% · 3%");
    }
}
