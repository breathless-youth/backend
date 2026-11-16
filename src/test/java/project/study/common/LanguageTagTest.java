package project.study.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageTagTest {

    @Test
    void 첫_태그의_주_언어를_소문자로_준다() {
        assertThat(LanguageTag.primary("ja-JP,ja;q=0.9,en;q=0.8")).isEqualTo("ja");
        assertThat(LanguageTag.primary("zh-Hant-TW")).isEqualTo("zh");
        assertThat(LanguageTag.primary("EN_us")).isEqualTo("en");
    }

    @Test
    void 헤더가_없거나_읽을_수_없으면_한국어다() {
        assertThat(LanguageTag.primary(null)).isEqualTo("ko");
        assertThat(LanguageTag.primary("  ")).isEqualTo("ko");
        assertThat(LanguageTag.primary("*")).isEqualTo("ko");
    }
}
