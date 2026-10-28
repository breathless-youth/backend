package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExperimentSection;
import project.study.metrics.report.analyzer.ExperimentSection.ArmStats;

class ExperimentRendererTest {

    @Test
    void 무엇을_비교하는지와_집단별_지표를_차이와_함께_쓴다() {
        assertThat(ExperimentRenderer.render(ReportSamples.experiment()).text()).isEqualTo("""
                        🔔 첫 세션 5분 이상 유도 Push 알림 A/B 테스트
                        • 대상 = 가입 후 첫 세션 미시작 사용자 (D0 가입 후 2시간 ~ D1 20시 기준)
                        • 방식 = 무작위 50:50 (알림 발송 · 미발송)
                        • 목표 = 첫 세션 순공 5분 달성, 그 결과 7일째 재방문
                        • 인원 = 발송 *40*명 · 미발송 *38*명
                        • ① 첫 세션 순공 5분 이상 (핵심) = 발송 *45%* (18/40) · 미발송 *29%* (11/38), 차이 +16%p
                        • ② 첫 세션 시작 = 발송 *75%* (30/40) · 미발송 *63%* (24/38), 차이 +12%p
                        • ③ D7 재방문 (판정) = 발송 *24%* (6/25) · 미발송 *13%* (3/24), 차이 +12%p
                            • D7 = 가입 7일째 당일 세션 시작 · 집계 완료자만 · 목표 집단별 680명 중 24명
                        • ④ 알림 권한 해제 (부작용) = 발송 *11%* (4/37) · 미발송 *5%* (2/38), 차이 +6%p
                            • 앱 삭제 추정 = 발송 3명 (비율 제외 · 미발송은 확인 불가)
                        > 목표 인원 전 차이는 우연 가능 · 결론 보류""");
    }

    @Test
    void 배정이_없으면_한_줄만_쓴다() {
        ArmStats none = new ArmStats(0, Rate.of(0, 0), Rate.of(0, 0), Rate.of(0, 0), Rate.of(0, 0), 0);

        assertThat(ExperimentRenderer.render(new ExperimentSection(Duration.ofHours(2), none, none, 680))
                        .text())
                .endsWith("• 인원 = 아직 없음");
    }

    @Test
    void 한쪽_분모가_없으면_차이를_쓰지_않는다() {
        ArmStats push = new ArmStats(2, Rate.of(1, 2), Rate.of(1, 2), Rate.of(0, 0), Rate.of(0, 2), 0);
        ArmStats control = new ArmStats(1, Rate.of(1, 1), Rate.of(0, 1), Rate.of(0, 0), Rate.of(0, 1), 0);

        assertThat(ExperimentRenderer.render(new ExperimentSection(Duration.ofMinutes(90), push, control, 680))
                        .text())
                .contains("• ③ D7 재방문 (판정) = 발송 *—* · 미발송 *—*\n")
                .contains("(1/1), 차이 -50%p")
                .contains("(D0 가입 후 90분 ~ D1 20시 기준)");
    }
}
