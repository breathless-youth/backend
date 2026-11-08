package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslationLanguageTest {
    @Test
    void selectsSupportedLanguageByQualityAndExcludesZero() {
        assertThat(TranslationLanguage.resolve("ja;q=0.5,en;q=0.9")).contains(TranslationLanguage.EN);
        assertThat(TranslationLanguage.resolve("en;q=0,ko;q=1")).contains(TranslationLanguage.KO);
        assertThat(TranslationLanguage.resolve("fr,en-US;q=0.8")).contains(TranslationLanguage.EN);
        assertThat(TranslationLanguage.resolve("ja,en")).contains(TranslationLanguage.JA);
    }

    @Test
    void missingMalformedAndUnsupportedHeadersCauseOriginalFallback() {
        for (String value : new String[] {null, "", "fr", "*", "en;q=oops", "zh-MO", "ko;q=2"}) {
            assertThat(TranslationLanguage.resolve(value))
                    .as("header %s", value)
                    .isEmpty();
        }
    }

    @Test
    void distinguishesSimplifiedTaiwanAndHongKongLocales() {
        Map<String, TranslationLanguage> aliases = Map.ofEntries(
                Map.entry("zh", TranslationLanguage.ZH_CN), Map.entry("zh-CN", TranslationLanguage.ZH_CN),
                Map.entry("zh-SG", TranslationLanguage.ZH_CN), Map.entry("zh-Hans", TranslationLanguage.ZH_CN),
                Map.entry("zh-TW", TranslationLanguage.ZH_TW), Map.entry("zh-Hant", TranslationLanguage.ZH_TW),
                Map.entry("zh-HK", TranslationLanguage.ZH_HK), Map.entry("zh-Hant-HK", TranslationLanguage.ZH_HK));
        aliases.forEach((header, expected) ->
                assertThat(TranslationLanguage.resolve(header)).contains(expected));
        assertThat(TranslationLanguage.ZH_HK.providerCode(TranslationProvider.GOOGLE))
                .isEqualTo("zh-TW");
        assertThat(TranslationLanguage.ZH_HK.providerCode(TranslationProvider.AZURE))
                .isEqualTo("zh-Hant");
        assertThat(TranslationLanguage.ZH_HK.providerCode(TranslationProvider.DEEPL))
                .isEqualTo("ZH-HANT");
    }

    @Test
    void rejectsNonHttpQualitySyntaxAndWhitespaceInsideLanguageToken() {
        for (String header : List.of("en;q=1e0", "en;q=+1", "en;q=0.1234", "e n", "en;q=.5", "en;q=1.001")) {
            assertThat(TranslationLanguage.resolve(header)).as(header).isEmpty();
        }
        assertThat(TranslationLanguage.resolve(" en-US ; q=0.900 , ja;q=0.5 ")).contains(TranslationLanguage.EN);
    }

    @Test
    void scriptAndRegionLocalesDoNotFallThroughToTheWrongChineseScript() {
        assertThat(TranslationLanguage.resolve("zh-Hans-CN")).contains(TranslationLanguage.ZH_CN);
        assertThat(TranslationLanguage.resolve("zh-Hans-SG")).contains(TranslationLanguage.ZH_CN);
        assertThat(TranslationLanguage.resolve("zh-Hant-TW,zh;q=0.9")).contains(TranslationLanguage.ZH_TW);
    }
}
