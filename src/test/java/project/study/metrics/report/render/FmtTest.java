package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;

class FmtTest {

    @Test
    void 날짜는_월_일_요일로_쓴다() {
        assertThat(Fmt.monthDayWeekday(LocalDate.of(2026, 10, 12))).isEqualTo("10/12(월)");
        assertThat(Fmt.range(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 29)))
                .isEqualTo("9/2~9/29");
    }

    @Test
    void 비율은_분모가_0이면_줄표다() {
        assertThat(Fmt.rate(Rate.of(8, 14))).isEqualTo("57% (8/14)");
        assertThat(Fmt.percent(Rate.of(8, 14))).isEqualTo("57%");
        assertThat(Fmt.rate(Rate.of(0, 0))).isEqualTo("—");
        assertThat(Fmt.percent(Rate.of(0, 0))).isEqualTo("—");
    }

    @Test
    void 시간은_시간과_분으로_쓴다() {
        assertThat(Fmt.duration(11 * 3600 + 20 * 60)).isEqualTo("11시간 20분");
        assertThat(Fmt.duration(3 * 3600)).isEqualTo("3시간");
        assertThat(Fmt.duration(50 * 60 + 59)).isEqualTo("50분");
        assertThat(Fmt.duration(0)).isEqualTo("0분");
    }

    @Test
    void 막대는_10칸이고_0이_아니면_한_칸은_채운다() {
        assertThat(Fmt.bar(26, 26)).isEqualTo("██████████");
        assertThat(Fmt.bar(10, 26)).isEqualTo("████░░░░░░");
        assertThat(Fmt.bar(2, 26)).isEqualTo("█░░░░░░░░░");
        assertThat(Fmt.bar(0, 26)).isEqualTo("░░░░░░░░░░");
        assertThat(Fmt.bar(0, 0)).isEqualTo("░░░░░░░░░░");
    }

    @Test
    void 한글은_두_칸으로_세어_맞춘다() {
        assertThat(Fmt.width("가입 다음 날")).isEqualTo(12);
        assertThat(Fmt.padRight("0일", 6)).isEqualTo("0일   ");
        assertThat(Fmt.padLeft("5%", 4)).isEqualTo("  5%");
    }

    @Test
    void 연속은_60일을_넘으면_줄여_쓴다() {
        assertThat(Fmt.streak(3)).isEqualTo("3일");
        assertThat(Fmt.streak(61)).isEqualTo("60일+");
    }
}
