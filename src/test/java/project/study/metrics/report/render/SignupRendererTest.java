package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;

class SignupRendererTest {

    @Test
    void 가입_스레드는_회원번호_시간대_막대_분야_유입을_들여쓴_목록으로_쓴다() {
        assertThat(SignupRenderer.render(ReportSamples.signup()).text()).isEqualTo("""
                        🕐 가입
                        • 어제 가입 = *38*명 (#503~#540)
                        • 가입하고 3시간 안에 공부 = *16%* (6/38)
                        • 최근 7일(10/6~10/12) 가입 *98*명, 시간대별
                            • 00~03시 ████░░░░░░ 10명 · 바로 공부 10% (1/10)
                            • 03~06시 █░░░░░░░░░ 2명 · 바로 공부 0/2
                            • 06~09시 ██░░░░░░░░ 5명 · 바로 공부 40% (2/5)
                            • 09~12시 ███░░░░░░░ 9명 · 바로 공부 11% (1/9)
                            • 12~15시 █████░░░░░ 12명 · 바로 공부 8% (1/12)
                            • 15~18시 █████░░░░░ 14명 · 바로 공부 14% (2/14)
                            • 18~21시 ████████░░ 20명 · 바로 공부 15% (3/20)
                            • 21~24시 ██████████ 26명 · 바로 공부 19% (5/26)
                        > 가입이 가장 많은 시간 21~24시 · 바로 공부 비율이 가장 높은 시간 06~09시 (2/5)
                        • 공부 분야 (최근 7일 가입)
                            • 수능 31% · 공무원 22% · 자격증 14% · 취업 8% · 어학 5% · 전문직 2% · 미입력 17%
                        • 유입 (최근 7일 가입, Amplitude 기준)
                            • 광고 클릭 = *39%* (37/94)
                            • UTM 상위 = instagram 24 · tiktok 9 · naver 3
                            • 미수집 = 4명""");
    }

    @Test
    void 가입이_없으면_막대와_분야를_쓰지_않는다() {
        List<HourBand> empty = List.of(
                new HourBand(0, 0, 0),
                new HourBand(3, 0, 0),
                new HourBand(6, 0, 0),
                new HourBand(9, 0, 0),
                new HourBand(12, 0, 0),
                new HourBand(15, 0, 0),
                new HourBand(18, 0, 0),
                new HourBand(21, 0, 0));
        SignupSection section = new SignupSection(
                ReportSamples.A,
                0,
                null,
                null,
                Rate.of(0, 0),
                0,
                empty,
                List.of(),
                new Attribution(0, 0, List.of(), 0));

        assertThat(SignupRenderer.render(section).text()).isEqualTo("""
                        🕐 가입
                        • 어제 가입 = *0*명
                        • 최근 7일(10/6~10/12) 가입 *0*명""");
    }

    @Test
    void Amplitude_속성이_하나도_없으면_유입은_미수집만_쓴다() {
        SignupSection base = ReportSamples.signup();
        SignupSection section = new SignupSection(
                base.anchor(),
                base.yesterdayCount(),
                base.minUserId(),
                base.maxUserId(),
                base.yesterdayQuick(),
                base.weekTotal(),
                base.bands(),
                base.categories(),
                new Attribution(98, 0, List.of(), 98));

        assertThat(SignupRenderer.render(section).text()).endsWith("• 유입 (최근 7일 가입, Amplitude 기준)\n    • 미수집 = 98명");
    }
}
