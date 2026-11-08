package project.study.user.nickname;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** {@link Nicknames} 규칙(다국어, 1글자 이상 40칸 이하). null은 통과(부분 수정에서 미변경). */
@Documented
@Constraint(validatedBy = NicknameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNickname {

    String message() default "닉네임은 한글·영문·일본어·한자·숫자·이모지·띄어쓰기·_ . -만 쓸 수 있고, 1글자 이상 40칸 이하여야 합니다(한글·한자·가나·이모지는 2칸)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
