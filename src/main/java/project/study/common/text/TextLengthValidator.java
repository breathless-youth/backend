package project.study.common.text;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TextLengthValidator implements ConstraintValidator<TextLength, String> {

    /** 글자 하나가 UTF-16 몇 단위까지 늘어날 수 있다고 보고 원문 상한을 잡는 배수. */
    private static final int RAW_CHARS_PER_GRAPHEME = 16;

    private int min;
    private int max;

    @Override
    public void initialize(TextLength constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() > (long) max * RAW_CHARS_PER_GRAPHEME) {
            return false;
        }
        String normalized = Graphemes.normalize(value);
        // 보이지 않는 글자만이면 글자 수를 0으로 본다 — 한글 채움 문자 하나로 min = 1을 통과하는 빈 이름을 막는다
        int length = Graphemes.isVisuallyBlank(normalized) ? 0 : Graphemes.count(normalized);
        return length >= min && length <= max;
    }
}
