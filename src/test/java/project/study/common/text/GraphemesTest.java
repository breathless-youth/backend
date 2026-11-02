package project.study.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 글자 단위(grapheme) 정규화·세기·첫 글자 (BY-1124). */
class GraphemesTest {

    private static final String FAMILY = "👨‍👩‍👧"; // 👨‍👩‍👧 (ZWJ 시퀀스)
    private static final String FLAG_KR = "🇰🇷"; // 🇰🇷 (지역 지시자 2개)
    private static final String HAN_NFD = "한"; // 한을 자모로 풀어쓴 NFD

    @Test
    void NFD_한글과_ZWJ_이모지는_한_글자다() {
        assertThat(Graphemes.count(Graphemes.normalize(HAN_NFD))).isEqualTo(1);
        assertThat(Graphemes.count(FAMILY)).isEqualTo(1);
        assertThat(Graphemes.count(FLAG_KR)).isEqualTo(1);
    }

    @Test
    void 앞뒤_공백을_빼고_NFC로_맞춘다() {
        assertThat(Graphemes.normalize("  공부 ")).isEqualTo("공부");
        assertThat(Graphemes.normalize(HAN_NFD)).isEqualTo("한");
        assertThat(Graphemes.normalize(null)).isNull();
    }

    @Test
    void 첫_글자는_이모지를_자르지_않는다() {
        assertThat(Graphemes.first(FAMILY + "공부")).isEqualTo(FAMILY);
        assertThat(Graphemes.first("")).isEmpty();
    }
}
