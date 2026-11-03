package project.study.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** @TextLength — 정규화한 뒤 글자 수 [min, max], 원문이 max * 16을 넘으면 실패 (BY-1124). */
class TextLengthValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static final String FAMILY = "👨‍👩‍👧"; // 👨‍👩‍👧
    private static final String COMBINING_ACUTE = "́";

    record Sample(@TextLength(min = 1, max = 20) String name) {}

    record OptionalSample(@TextLength(max = 20) String description) {}

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void 이모지_조합_20개는_통과한다() {
        assertThat(VALIDATOR.validate(new Sample(FAMILY.repeat(20)))).isEmpty();
    }

    @Test
    void 글자_21개는_실패한다() {
        assertThat(VALIDATOR.validate(new Sample("가".repeat(21)))).hasSize(1);
    }

    @Test
    void 공백만은_min_1에서_실패한다() {
        assertThat(VALIDATOR.validate(new Sample("   "))).hasSize(1);
    }

    @Test
    void null은_통과한다() {
        assertThat(VALIDATOR.validate(new Sample(null))).isEmpty();
    }

    @Test
    void 원문이_너무_길면_실패한다() {
        // 20글자(통과)지만 원문은 UTF-16 420자 > 20 * 16 — 원문 길이 방어만으로 실패해야 한다
        String raw = ("a" + COMBINING_ACUTE.repeat(20)).repeat(20);

        assertThat(Graphemes.count(Graphemes.normalize(raw))).isEqualTo(20);
        assertThat(VALIDATOR.validate(new Sample(raw))).hasSize(1);
    }

    @Test
    void 기본_메시지에_최소와_최대가_들어간다() {
        var violations = VALIDATOR.validate(new Sample("가".repeat(21)));

        assertThat(violations)
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage()).isEqualTo("1~20자여야 합니다"));
    }

    // 한글 채움 문자(U+3164·U+115F·U+1160·U+FFA0)·제로폭 공백(U+200B)·줄바꿈 없는 공백(U+00A0)·전각 공백은 strip()이 못 지우거나
    // 글자 한 개로 세어져 "빈 이름"이 min = 1을 통과한다
    @ParameterizedTest
    @ValueSource(
            strings = {
                "\u3164",
                "\u200B",
                "\u00A0",
                "\u3164\u200B\u00A0",
                "\u115F",
                "\u1160",
                "\uFFA0",
                "\u2003",
                "\u3000\u3164",
                "\u200D",
                "\u0007"
            })
    void 보이지_않는_글자만이면_min_1에서_실패한다(String invisible) {
        assertThat(VALIDATOR.validate(new Sample(invisible))).hasSize(1);
    }

    @Test
    void 보이는_글자가_하나라도_있으면_통과한다() {
        assertThat(VALIDATOR.validate(new Sample("a\u200B"))).isEmpty();
        assertThat(VALIDATOR.validate(new Sample("\u3164가"))).isEmpty();
        // ZWJ가 들어간 이모지 조합은 보이는 글자다
        assertThat(VALIDATOR.validate(new Sample(FAMILY))).isEmpty();
    }

    @Test
    void 보이지_않는_글자만이어도_min_0이면_통과한다() {
        // 글자 수를 0으로 볼 뿐이라 선택 입력은 그대로 비워 둘 수 있다
        assertThat(VALIDATOR.validate(new OptionalSample("\u3164\u200B"))).isEmpty();
    }
}
