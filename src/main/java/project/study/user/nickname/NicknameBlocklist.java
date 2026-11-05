package project.study.user.nickname;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import project.study.common.text.Graphemes;

/**
 * 닉네임 금칙어와 운영자 사칭 이름 (BY-1135). 비용이 들지 않게 서버에 둔 목록만으로 판정한다(출처·라이선스는 `nickname/blocklist/NOTICE.md`).
 *
 * <p>입력은 {@link Nicknames#normalize}한 값이다. 소문자로 접고, 라틴 글자의 악센트·닮은꼴(`fück`, `admın`), 띄어쓰기·{@code _ . -}로 끊어 쓴 것
 * (`f_u_c_k`), 흔한 숫자 바꿔치기(`sh1t`)를 되돌린 뒤 본다.
 *
 * <ul>
 *   <li>영어 금칙어는 단어 단위로만 비교해, 정상 단어 속에 우연히 든 경우(`assassin`, `Scunthorpe`)는 막지 않는다. 정상 단어에 들어갈 일이 없는
 *       말(`en-substring.txt`)만 단어 속에 들어 있어도 막는다.
 *   <li>한자·가나와 세 글자 이상 한글 금칙어는 띄어쓰기를 지운 뒤 들어 있기만 해도 막는다. 한글 두 글자 금칙어는 띄어쓰기 단위 안에서만 찾고
 *       (`다시 발견`이 `시발`에 걸리지 않게), 한 글자 금칙어는 닉네임 전체가 그 글자일 때만 막는다(`씹는`, `奶茶`, `个性`).
 *   <li>금칙어를 품은 정상 단어(`allow.txt`: `시발점`, `시바견`, `Matsushita`)는 지운 뒤 들어 있는지 본다.
 *   <li>운영자로 보이는 이름(`reserved.txt` — 영어는 단어 단위, 한중일은 들어 있으면)과 앱 이름 `포메`를 단독으로 쓰거나 "공식·운영·팀·관리"로
 *       시작하는 이름을 막는다.
 *       `포메라니안`처럼 다른 단어 속에 든 앱 이름은 막지 않는다.
 * </ul>
 */
public final class NicknameBlocklist {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s_.\\-]+");
    private static final int MIN_PHRASE_CONTAINS = 6; // 이보다 짧은 영어 구절은 통째로 같을 때만 막는다(`g-spot`이 `bigspotter`에 걸리지 않게)
    private static final String BRAND = "포메";
    private static final List<String> BRAND_SUFFIXES =
            List.of("", "공식", "운영", "운영자", "운영진", "운영팀", "팀", "관리", "관리자", "official", "team", "admin", "staff");
    private static final List<String> BRAND_PREFIXES = List.of("공식", "official");

    // 악센트를 떼도 남는, 기본 라틴 글자와 생김새가 같은 글자
    private static final Map<Integer, String> LATIN_LOOKALIKES = Map.ofEntries(
            Map.entry((int) 'ı', "i"),
            Map.entry((int) 'ł', "l"),
            Map.entry((int) 'ø', "o"),
            Map.entry((int) 'đ', "d"),
            Map.entry((int) 'ð', "d"),
            Map.entry((int) 'ħ', "h"),
            Map.entry((int) 'ŧ', "t"),
            Map.entry((int) 'ƒ', "f"),
            Map.entry((int) 'ß', "ss"),
            Map.entry((int) 'æ', "ae"),
            Map.entry((int) 'œ', "oe"),
            Map.entry((int) 'þ', "th"));

    private static final Set<String> LATIN_WORDS = new HashSet<>();
    private static final List<String> LATIN_PHRASES = new ArrayList<>();
    private static final Set<String> OTHER_SINGLES = new HashSet<>();
    private static final List<String> OTHER_SUBSTRINGS = new ArrayList<>();
    // 한글 두 글자 금칙어는 단어 경계를 넘어 우연히 생기기 쉬워(`다시 발견` → 시발, `학자 지망생` → 자지) 띄어쓰기 단위 안에서만 찾는다.
    // 띄어 써서 피해 가는 게 뻔한 몇 개만 띄어쓰기를 지운 문자열에서도 찾는다
    private static final List<String> HANGUL_PAIRS = new ArrayList<>();
    private static final Set<String> HANGUL_PAIRS_ACROSS_SPACES = Set.of("씨발", "씨팔", "씨빨", "좆같");
    private static final List<String> LATIN_SUBSTRINGS = read("en-substring.txt");
    private static final Set<String> RESERVED = Set.copyOf(read("reserved.txt"));
    // 한글·한자·가나 운영자 단어는 붙여 쓰므로 들어 있기만 해도 막는다(`수달운영자`, `官方客服`)
    private static final List<String> RESERVED_NON_LATIN = RESERVED.stream()
            .filter(word -> word.chars().anyMatch(c -> c >= 0x80))
            .toList();
    private static final List<String> ALLOW = read("allow.txt");

    static {
        for (String file : List.of("ko.txt", "en.txt", "ja.txt", "zh.txt")) {
            read(file).forEach(NicknameBlocklist::classify);
        }
    }

    private NicknameBlocklist() {}

    public static boolean isBlocked(String normalized) {
        Forms forms = Forms.of(normalized);
        return isLatinBlocked(forms) || isNonLatinBlocked(forms) || isImpersonation(forms);
    }

    /** 한 닉네임을 비교용으로 바꾼 여러 꼴 — `checked*`는 금칙어를 품은 정상 단어(allow)를 지운 값이다. */
    private record Forms(
            String squashed,
            String deleeted,
            String checked,
            String checkedDeleeted,
            List<String> tokens,
            List<String> checkedTokens) {

        static Forms of(String normalized) {
            String folded = latinSkeleton(normalized.toLowerCase(Locale.ROOT));
            String squashed = SEPARATORS.matcher(folded).replaceAll("");
            String deleeted = deleet(squashed);
            List<String> rawTokens = Arrays.stream(SEPARATORS.split(folded))
                    .filter(token -> !token.isEmpty())
                    .toList();
            return new Forms(
                    squashed,
                    deleeted,
                    withoutAllowed(squashed),
                    withoutAllowed(deleeted),
                    rawTokens.stream().map(NicknameBlocklist::deleet).toList(),
                    rawTokens.stream().map(NicknameBlocklist::withoutAllowed).toList());
        }
    }

    private static boolean isLatinBlocked(Forms forms) {
        return forms.tokens().stream().anyMatch(LATIN_WORDS::contains)
                || LATIN_WORDS.contains(forms.deleeted())
                || LATIN_PHRASES.stream()
                        .anyMatch(phrase -> phrase.length() >= MIN_PHRASE_CONTAINS
                                ? forms.checkedDeleeted().contains(phrase)
                                : forms.deleeted().equals(phrase))
                || LATIN_SUBSTRINGS.stream().anyMatch(forms.checkedDeleeted()::contains);
    }

    private static boolean isNonLatinBlocked(Forms forms) {
        return HANGUL_PAIRS.stream()
                        .anyMatch(pair -> HANGUL_PAIRS_ACROSS_SPACES.contains(pair)
                                ? forms.checked().contains(pair)
                                : forms.checkedTokens().stream().anyMatch(token -> token.contains(pair)))
                || OTHER_SUBSTRINGS.stream().anyMatch(forms.checked()::contains)
                || OTHER_SINGLES.contains(forms.squashed());
    }

    private static boolean isImpersonation(Forms forms) {
        return forms.tokens().stream().anyMatch(RESERVED::contains)
                || RESERVED.contains(forms.squashed())
                || RESERVED_NON_LATIN.stream().anyMatch(forms.checked()::contains)
                || isBrand(forms.squashed());
    }

    // `포메` 단독, `포메` + 공식·운영·팀…으로 시작(`포메공식계정`, `포메팀장`), 공식·official + `포메`로 시작. `포메라니안`은 통과
    private static boolean isBrand(String squashed) {
        return squashed.equals(BRAND)
                || BRAND_SUFFIXES.stream()
                        .filter(suffix -> !suffix.isEmpty())
                        .anyMatch(suffix -> squashed.startsWith(BRAND + suffix))
                || BRAND_PREFIXES.stream().anyMatch(prefix -> squashed.startsWith(prefix + BRAND));
    }

    private static String withoutAllowed(String text) {
        String result = text;
        for (String word : ALLOW) {
            result = result.replace(word, "");
        }
        return result;
    }

    // 라틴 글자만 악센트를 떼고 생김새가 같은 글자를 기본 글자로 바꾼다(`admın` → admin, `fück` → fuck, `ƒuck` → fuck).
    // 한글·한자·가나는 건드리지 않는다(NFD가 한글 음절을 자모로 쪼개므로 글자마다 라틴인지 보고 바꾼다)
    private static String latinSkeleton(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp < 0x80 || Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) {
                result.appendCodePoint(cp);
                return;
            }
            String base = LATIN_LOOKALIKES.getOrDefault(cp, null);
            if (base != null) {
                result.append(base);
                return;
            }
            Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFD)
                    .codePoints()
                    .filter(part -> Character.getType(part) != Character.NON_SPACING_MARK)
                    .forEach(result::appendCodePoint);
        });
        return result.toString();
    }

    // 닉네임에 흔한 숫자 바꿔치기만 되돌린다(0→o 1→i 3→e 4→a 5→s 7→t)
    private static String deleet(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            result.append(
                    switch (c) {
                        case '0' -> 'o';
                        case '1' -> 'i';
                        case '3' -> 'e';
                        case '4' -> 'a';
                        case '5' -> 's';
                        case '7' -> 't';
                        default -> c;
                    });
        }
        return result.toString();
    }

    private static void classify(String entry) {
        String squashed = SEPARATORS.matcher(entry).replaceAll("");
        if (squashed.isEmpty()) {
            return;
        }
        if (entry.chars().allMatch(c -> c < 0x80)) {
            if (SEPARATORS.matcher(entry).find()) {
                LATIN_PHRASES.add(squashed);
            } else {
                LATIN_WORDS.add(squashed);
            }
        } else if (Graphemes.count(squashed) == 2 && squashed.chars().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3)) {
            HANGUL_PAIRS.add(squashed);
        } else if (Graphemes.count(squashed) >= 2 || Character.isExtendedPictographic(squashed.codePointAt(0))) {
            OTHER_SUBSTRINGS.add(squashed);
        } else {
            OTHER_SINGLES.add(squashed);
        }
    }

    // 목록은 저장할 때 NFKC·소문자로 정리해 두었다. 빈 줄은 건너뛴다
    private static List<String> read(String file) {
        String path = "nickname/blocklist/" + file;
        try (InputStream in = NicknameBlocklist.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("닉네임 금칙어 목록이 없다: " + path);
            }
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .map(String::strip)
                    .filter(word -> !word.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
