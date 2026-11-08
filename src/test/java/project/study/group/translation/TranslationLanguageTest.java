package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        for (String value : new String[] {null, "", "fr", "*", "en;q=oops", "zh-CN", "ko;q=2"}) {
            assertThat(TranslationLanguage.resolve(value))
                    .as("header %s", value)
                    .isEmpty();
        }
    }

    @Test
    void traditionalChineseUsesTheSharedStandardModel() {
        assertThat(TranslationLanguage.ZH_TW.providerCode(TranslationProvider.GOOGLE))
                .isEqualTo("zh-TW");
        assertThat(TranslationLanguage.ZH_TW.providerCode(TranslationProvider.AZURE))
                .isEqualTo("zh-Hant");
        assertThat(TranslationLanguage.ZH_TW.providerCode(TranslationProvider.DEEPL))
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
        assertThat(TranslationLanguage.resolve("zh-Hans-CN")).isEmpty();
        assertThat(TranslationLanguage.resolve("zh-Hans-SG")).isEmpty();
        assertThat(TranslationLanguage.resolve("zh-Hant-TW,zh;q=0.9")).contains(TranslationLanguage.ZH_TW);
    }

    @Test
    void traditionalAliasesResolveToTheSingleFrontendLocale() {
        for (String header :
                List.of("zh-TW", "zh-Hant", "zh-HK", "zh-MO", "zh-Hant-TW", "zh-Hant-HK", "zh-Hant-MO", "zh-Hant-CN")) {
            assertThat(TranslationLanguage.resolve(header).map(TranslationLanguage::locale))
                    .as(header)
                    .contains("zh-TW");
        }
    }

    @Test
    void simplifiedChineseIsNotAnApiTarget() {
        for (String header : List.of("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN")) {
            assertThat(TranslationLanguage.resolve(header)).as(header).isEmpty();
        }
    }
}
