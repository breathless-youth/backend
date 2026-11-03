package project.study.acquisition.referrer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleAdLabelTest {

    @ParameterizedTest
    @ValueSource(strings = {"g_planner", "g_a", "g_0", "g_study_v2", "g_______"})
    void 형식에_맞으면_그대로_라벨이다(String value) {
        assertThat(GoogleAdLabel.of(value)).isEqualTo(value);
    }

    @Test
    void 접두어_뒤_30자까지_라벨이다() {
        String longest = "g_" + "a".repeat(30);
        assertThat(GoogleAdLabel.of(longest)).isEqualTo(longest);
        assertThat(GoogleAdLabel.of(longest + "a")).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "g_",
                "planner",
                "G_planner",
                "g_Planner",
                "g_plan-ner",
                "g_plan ner",
                "g_계획",
                "aos_v1",
                " g_planner",
                "g_planner\n"
            })
    void 형식이_틀리면_라벨이_없다(String value) {
        assertThat(GoogleAdLabel.of(value)).isNull();
    }
}
