package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 테스트용 — 언어별 자동 닉네임 조합 전체와 판별. */
public final class AutoNicknameFixtures {

    // 조합이 겹치면 1만 개보다 작아진다 — AutoNicknamesTest가 크기로 확인한다
    private static final Map<NicknameLanguage, Set<String>> ALL = combineAll();

    private AutoNicknameFixtures() {}

    /** 그 언어의 꾸미는 말 × 동물 전체 조합 (수정 불가). */
    public static Set<String> all(NicknameLanguage language) {
        return ALL.get(language);
    }

    /** 어느 언어든 기본 닉네임 조합이어야 한다. */
    public static void assertAuto(String nickname) {
        assertThat(ALL.values().stream().anyMatch(all -> all.contains(nickname)))
                .as("자동 닉네임 조합이어야 한다: %s", nickname)
                .isTrue();
    }

    private static Map<NicknameLanguage, Set<String>> combineAll() {
        Map<NicknameLanguage, Set<String>> all = new EnumMap<>(NicknameLanguage.class);
        Arrays.stream(NicknameLanguage.values()).forEach(language -> all.put(language, combine(language)));
        return all;
    }

    private static Set<String> combine(NicknameLanguage language) {
        Set<String> all = new HashSet<>();
        for (String modifier : AutoNicknames.modifiers(language)) {
            for (String animal : AutoNicknames.animals(language)) {
                all.add(AutoNicknames.compose(language, modifier, animal));
            }
        }
        return Set.copyOf(all);
    }
}
