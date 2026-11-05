package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 언어별 기본 닉네임 목록 (BY-1135). */
class AutoNicknamesTest {

    @ParameterizedTest
    @EnumSource(NicknameLanguage.class)
    void 꾸미는_말과_동물은_각각_100개이고_중복이_없다(NicknameLanguage language) {
        assertThat(AutoNicknames.modifiers(language)).hasSize(100).doesNotHaveDuplicates();
        assertThat(AutoNicknames.animals(language)).hasSize(100).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @EnumSource(NicknameLanguage.class)
    void 만_개_조합이_서로_다르고_모두_닉네임_규칙과_30칸_안에_든다(NicknameLanguage language) {
        Set<String> all = AutoNicknameFixtures.all(language);

        assertThat(all).hasSize(10_000);
        assertThat(all).allSatisfy(nickname -> {
            assertThat(Nicknames.isAcceptable(nickname)).as(nickname).isTrue();
            assertThat(Nicknames.width(nickname)).as(nickname).isLessThanOrEqualTo(30);
        });
    }

    @Test
    void 언어마다_정한_구분자로_잇는다() {
        assertThat(AutoNicknames.compose(NicknameLanguage.JA, "勉強中の", "ペンギン")).isEqualTo("勉強中のペンギン");
        assertThat(AutoNicknames.compose(NicknameLanguage.EN, "Curious", "Hedgehog"))
                .isEqualTo("Curious Hedgehog");
        assertThat(AutoNicknames.compose(NicknameLanguage.KO, "벼락치기하는", "수달")).isEqualTo("벼락치기하는 수달");
        assertThat(AutoNicknames.compose(NicknameLanguage.ZH_CN, "熬夜复习的", "考拉")).isEqualTo("熬夜复习的考拉");
    }

    @ParameterizedTest
    @EnumSource(NicknameLanguage.class)
    void 뽑은_닉네임은_그_언어의_조합이다(NicknameLanguage language) {
        Random random = new Random(42);
        Set<String> all = AutoNicknameFixtures.all(language);

        for (int i = 0; i < 100; i++) {
            assertThat(all).contains(AutoNicknames.random(language, random));
        }
    }
}
