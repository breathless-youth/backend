package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TranslationTextTest {
    @Test
    void countsUnicodeCodePointsInsteadOfUtf16Units() {
        assertThat(new TranslationText(1, TranslationField.NAME, "A😀 가").characters())
                .isEqualTo(4);
        assertThat(new TranslationText(1, TranslationField.NAME, "가").characters())
                .isEqualTo(2);
    }

    @Test
    void fingerprintsExactSourceWithoutRenormalizing() {
        TranslationText source = new TranslationText(1, TranslationField.NAME, "가");
        assertThat(source.sourceHash()).hasSize(64);
        assertThat(source.sourceHash()).isEqualTo(new TranslationText(2, TranslationField.NAME, "가").sourceHash());
        assertThat(source.sourceHash()).isNotEqualTo(new TranslationText(1, TranslationField.NAME, "가").sourceHash());
    }
}
