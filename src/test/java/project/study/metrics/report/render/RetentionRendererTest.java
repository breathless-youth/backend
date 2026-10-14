package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetentionRendererTest {

    @Test
    void 리텐션_스레드는_기준과_구간별_비율_아래에_지난주_값과_가입_기간을_쓴다() {
        assertThat(RetentionRenderer.render(ReportSamples.retention()).text()).isEqualTo("""
                        📈 다시 와서 공부한 비율
                        • 기준
                            • 공부 = 그날 10분 이상 공부한 날
                            • 구간이 다 끝난 최근 28일치 가입자만 셈
                        • 가입 다음 날 = *10%* (27/268)
                            • 지난주 11% · 가입 9/14~10/11
                        • 7~13일 뒤 = *6%* (16/252)
                            • 지난주 5% · 가입 9/2~9/29
                            • 7일 중 하루라도 공부하면 셈. 6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)
                        • 28~34일 뒤 = *3%* (6/190)
                            • 지난주 3% · 가입 8/12~9/8
                        • 첫 주에 한 번도 공부 안 한 사람 = *75%* (199/265)
                            • 가입 9/9~10/6""");
    }
}
