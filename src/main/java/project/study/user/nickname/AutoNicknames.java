package project.study.user.nickname;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 가입 때 자동으로 주는 닉네임 — "꾸미는 말 + 동물" (BY-1135). 숫자가 붙은 `포메12345`가 봇처럼 보여서 바꿨다.
 *
 * <p>언어마다 꾸미는 말 100 × 동물 100 = 1만 개이고, 목록은 `src/main/resources/nickname/auto/<언어>/`에 한 줄에 한 단어로 둔다.
 * 꾸미는 말은 나이와 상관없이 공감할 공부 장면, 동물은 그 나라 누구나 아는 귀엽거나 멋진 동물만 고르고, 그 언어에서 욕·은어로 쓰이는 말은
 * 뺐다(근거는 `docs/superpowers/specs/2026-11-04-by1135-nickname-lists/`). 어느 조합이든 30칸 이하다 — 단어를 바꾸면 AutoNicknamesTest가
 * 길이·규칙·금칙어를 다시 확인한다. 기존 유저를 바꾼 마이그레이션(V61)에는 한국어 목록의 같은 시점 사본이 있다.
 */
public final class AutoNicknames {

    private static final int WORDS_PER_LIST = 100;
    private static final Map<NicknameLanguage, Words> WORDS = load();

    private AutoNicknames() {}

    public static List<String> modifiers(NicknameLanguage language) {
        return WORDS.get(language).modifiers();
    }

    public static List<String> animals(NicknameLanguage language) {
        return WORDS.get(language).animals();
    }

    public static String compose(NicknameLanguage language, String modifier, String animal) {
        return modifier + language.joiner() + animal;
    }

    public static String random(NicknameLanguage language, RandomGenerator random) {
        Words words = WORDS.get(language);
        return compose(
                language,
                words.modifiers().get(random.nextInt(words.modifiers().size())),
                words.animals().get(random.nextInt(words.animals().size())));
    }

    private record Words(List<String> modifiers, List<String> animals) {}

    private static Map<NicknameLanguage, Words> load() {
        Map<NicknameLanguage, Words> words = new EnumMap<>(NicknameLanguage.class);
        for (NicknameLanguage language : NicknameLanguage.values()) {
            String dir = "nickname/auto/" + language.code() + "/";
            words.put(language, new Words(read(dir + "modifiers.txt"), read(dir + "animals.txt")));
        }
        return words;
    }

    // 목록이 빠지거나 개수가 틀리면 기동을 막는다 — 조용히 적은 목록으로 발급하지 않게
    private static List<String> read(String path) {
        try (InputStream in = AutoNicknames.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("자동 닉네임 목록이 없다: " + path);
            }
            List<String> words = Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .map(String::strip)
                    .filter(word -> !word.isEmpty())
                    .toList();
            if (words.size() != WORDS_PER_LIST) {
                throw new IllegalStateException(
                        "자동 닉네임 목록은 " + WORDS_PER_LIST + "개여야 한다: " + path + " = " + words.size());
            }
            return words;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
