package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.translation.TranslationLanguage.*;

import org.junit.jupiter.api.Test;

class SourceLanguageTest {
    @Test
    void mostlyHangulTextIsAlreadyKoreanEvenWithLatinWordsAndNumbers() {
        assertThat(SourceLanguage.needsNoTranslation("새벽 공부방", KO)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Python 알고리즘 같이 풀어요", KO)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("매일 6시 50분 공부📚", KO)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Night Owls Study", KO)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("早朝の自習室", KO)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("Please join 우리 모임", KO)).isFalse();
    }

    @Test
    void kanaMarksJapaneseButKanjiOnlyIsNotAssumed() {
        assertThat(SourceLanguage.needsNoTranslation("毎朝6時に勉強します", JA)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Pythonのアルゴリズム", JA)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("東京大学", JA)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("새벽 공부방", JA)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("Morning Study の会", JA)).isFalse();
    }

    @Test
    void onlyPlainAsciiLettersCountAsEnglish() {
        assertThat(SourceLanguage.needsNoTranslation("Night Owls Study", EN)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Cameras on, mics off. 50 min! 📚", EN))
                .isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Python 알고리즘", EN)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("Étude du soir", EN)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("Cafe\u0301 du soir", EN)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("Study with me ❤️", EN)).isTrue();
        assertThat(SourceLanguage.needsNoTranslation("Учимся вместе", EN)).isFalse();
    }

    @Test
    void traditionalChineseIsAlwaysSentBecauseSimplifiedLooksTheSame() {
        assertThat(SourceLanguage.needsNoTranslation("黎明自習室", ZH_TW)).isFalse();
        assertThat(SourceLanguage.needsNoTranslation("黎明自习室", ZH_TW)).isFalse();
    }

    @Test
    void textWithoutLettersHasNothingToTranslateInAnyLanguage() {
        for (var language : TranslationLanguage.values()) {
            assertThat(SourceLanguage.needsNoTranslation("2026 📚🔥 !!", language))
                    .isTrue();
        }
    }
}
