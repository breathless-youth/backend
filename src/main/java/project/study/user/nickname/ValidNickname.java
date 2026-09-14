package project.study.user.nickname;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 한글·영문·숫자·이모지·띄어쓰기만, 앞뒤 공백을 제외하고 2~12자(글자 단위). null은 통과(부분 수정에서 미변경). */
@Documented
@Constraint(validatedBy = NicknameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNickname {

    String message() default "닉네임은 한글·영문·숫자·이모지·띄어쓰기만 쓸 수 있고, 앞뒤 공백을 제외하고 2~12자여야 합니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
