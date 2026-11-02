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
        int length = Graphemes.count(Graphemes.normalize(value));
        return length >= min && length <= max;
    }
}
