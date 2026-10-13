package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetentionRendererTest {

    @Test
    void 리텐션_스레드는_구간_정의와_지난주_값을_쓴다() {
        String text = RetentionRenderer.render(ReportSamples.retention());

        assertThat(text.lines())
                .contains(
                        "📈 다시 와서 공부한 비율",
                        "공부 = 그날 10분 이상 공부한 날 · 구간이 다 끝난 최근 28일치 가입자만 셈",
                        "가입 다음 날  10% (27/268)   지난주  11%   [가입 9/14~10/11]",
                        "→ 7~13일 뒤 = 가입 7일 뒤부터 13일 뒤까지 7일 중 하루라도 공부한 사람.",
                        "  6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)",
                        "첫 주에 한 번도 공부 안 한 사람: 75% (199/265) [가입 9/9~10/6]");
        assertThat(text).contains("[가입 9/2~9/29]").contains("[가입 8/12~9/8]");
    }
}
