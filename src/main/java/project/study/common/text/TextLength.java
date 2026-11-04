package project.study.common.text;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NFC 정규화 + 앞뒤 공백 제거 뒤 글자 단위(grapheme) 개수가 [min, max]여야 한다. null은 통과(필수는 {@code @NotNull}을 같이
 * 단다). 정규화 전 원문이 {@code max * 16}(UTF-16 단위)을 넘으면 글자 수와 상관없이 실패한다 — 거대 입력 방어. 공백·서식·제어 문자와 한글
 * 채움 문자 같이 보이지 않는 글자만이면 글자 수를 0으로 본다({@code Graphemes.isVisuallyBlank}).
 */
@Documented
@Constraint(validatedBy = TextLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface TextLength {

    int min() default 0;

    int max();

    String message() default "{min}~{max}자여야 합니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
