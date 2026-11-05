package project.study.user.nickname;

import java.text.Normalizer;
import java.util.List;
import project.study.common.text.Graphemes;

/**
 * 닉네임 규칙 (BY-647 → BY-1135): 한글·영문(악센트 포함)·일본어(히라가나·가타카나)·한자·숫자·이모지·띄어쓰기·{@code _ . -}만 허용하고,
 * 앞뒤 공백을 잘라낸 뒤 1글자 이상 40칸 이하. 한글 음절·가나·장음 부호·한자·이모지는 2칸, 나머지는 1칸으로 센다 — 화면에서 차지하는 폭이
 * 두 배라서다(랭킹 1~3등 칸이 가장 좁다).
 *
 * <p>허용 목록 방식이라 그 밖의 문장부호(가운뎃점 {@code ・} 포함)·다른 문자·보이지 않는 문자(제로폭·제어·서식 문자)는 지우지 않고 거부한다.
 * 이모지는 조합(ZWJ 시퀀스, 피부색, 국기, 키캡, 🏴 태그)을 한 글자로 세되 구성 요소가 전부 이모지 부품일 때만 인정한다.
 *
 * <p>정규화는 NFKC다. 자모로 입력된 한글을 완성형으로 합치고, 전각 영문({@code Ｏｔｔｅｒ})·반각 가타카나를 보통 글자로 바꿔 같은 이름이
 * 다른 글자로 두 번 쓰이지 않게 한다(대소문자는 DB가 접어서 본다). 글자 수만으로는 ZWJ 사슬을 막지 못하므로 원문 길이와 코드포인트 수에도 상한을 둔다.
 */
public final class Nicknames {

    public static final int MIN_LENGTH = 1;
    /** 칸 상한 — 한글·한자·가나 20자, 영문 40자. */
    public static final int MAX_WIDTH = 40;
    /** 정규화 전 원문 길이(UTF-16 단위) 상한 — 정규화 비용 자체를 막는 빠른 검사. */
    public static final int MAX_RAW_CHARS = 256;
    /** 정규화 후 코드포인트 상한 — 가족 이모지(7)·지역 깃발(7)을 여럿 넣어도 넉넉하다. */
    public static final int MAX_CODE_POINTS = 100;

    private static final int SPACE = ' ';
    private static final int PROLONGED_SOUND_MARK = 0x30FC; // ー — 문자 체계가 COMMON이라 따로 허용한다
    private static final int LATIN_LETTER_LIMIT = 0x024F; // 라틴 확장-B까지(악센트 글자). 그 뒤는 IPA·장식 글자
    private static final int ZWJ = 0x200D;
    private static final int VS16 = 0xFE0F;
    private static final int KEYCAP = 0x20E3;
    private static final int BLACK_FLAG = 0x1F3F4;

    private Nicknames() {}

    /** NFKC 정규화 + 앞뒤 공백 제거. null은 null(부분 수정에서 미변경). 저장 직전에도 같은 정규화를 적용한다. */
    public static String normalize(String raw) {
        return raw == null
                ? null
                : Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
    }

    /** 원문 상한 → 정규화 → 코드포인트 상한 → 1글자 이상 → 40칸 이하 → 모든 글자가 허용 목록 안. */
    public static boolean isAcceptable(String raw) {
        if (raw.length() > MAX_RAW_CHARS) {
            return false;
        }
        String normalized = normalize(raw);
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            return false;
        }
        List<String> graphemes = Graphemes.split(normalized);
        if (graphemes.size() < MIN_LENGTH || width(graphemes) > MAX_WIDTH) {
            return false;
        }
        return graphemes.stream().allMatch(Nicknames::isAllowed);
    }

    /** 칸 수 — 한글 음절·가나·장음 부호·한자·이모지 글자는 2칸, 나머지 글자 단위는 1칸. 정규화한 값에 쓴다. */
    public static int width(String normalized) {
        return width(Graphemes.split(normalized));
    }

    /** 눈에 보이는 글자 단위(grapheme) 개수. */
    public static int length(String nickname) {
        return Graphemes.count(nickname);
    }

    /** 아바타 이니셜 — 첫 글자 단위. 이모지가 앞에 와도 서로게이트 절반이 잘리지 않는다. */
    public static String initialOf(String nickname) {
        return Graphemes.first(nickname);
    }

    private static int width(List<String> graphemes) {
        return graphemes.stream().mapToInt(Nicknames::widthOf).sum();
    }

    private static int widthOf(String grapheme) {
        int[] cps = grapheme.codePoints().toArray();
        if (cps.length > 1) {
            return isEmojiSequence(cps) ? 2 : 1;
        }
        int cp = cps[0];
        return isHangul(cp) || isKana(cp) || isHan(cp) || isPictographic(cp) ? 2 : 1;
    }

    // 한 글자 단위가 허용 목록 안인가 — 단일 코드포인트면 허용 문자, 여러 개면 이모지 조합
    private static boolean isAllowed(String grapheme) {
        int[] cps = grapheme.codePoints().toArray();
        if (cps.length == 1) {
            int cp = cps[0];
            return isAllowedSymbol(cp) || isAllowedLetter(cp) || isPictographic(cp);
        }
        return isEmojiSequence(cps);
    }

    // 띄어쓰기와 아이디에 흔한 _ . - 만. 그 밖의 문장부호는 받지 않는다
    private static boolean isAllowedSymbol(int cp) {
        return cp == SPACE || cp == '_' || cp == '.' || cp == '-';
    }

    private static boolean isAllowedLetter(int cp) {
        return isHangul(cp) || isAsciiLetterOrDigit(cp) || isLatinLetter(cp) || isKana(cp) || isHan(cp);
    }

    private static boolean isLatinLetter(int cp) {
        return cp <= LATIN_LETTER_LIMIT
                && Character.isLetter(cp)
                && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
    }

    // 히라가나·가타카나 글자(반복 부호 ゝ·ヽ 포함)와 장음 부호. 가운뎃점 ・·탁점 단독 ゛은 문장부호·기호라 빠진다
    private static boolean isKana(int cp) {
        if (cp == PROLONGED_SOUND_MARK) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return Character.isLetter(cp)
                && (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA);
    }

    // 한자(간체·번체·확장 영역)와 반복 부호 々
    private static boolean isHan(int cp) {
        return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    // 이모지 조합: 국기(지역 지시자 2개), 키캡([0-9#*] FE0F? ⃣), 🏴 태그 시퀀스, 그 밖엔 그림 이모지로 시작해
    // 부품(그림 이모지·피부색·VS16·사이의 ZWJ)만으로 이어진 시퀀스
    private static boolean isEmojiSequence(int[] cps) {
        int first = cps[0];
        if (isRegionalIndicator(first)) {
            return isFlag(cps);
        }
        if (isKeycapBase(first)) {
            return isKeycap(cps);
        }
        if (first == BLACK_FLAG && isTag(cps[1])) {
            return isTagSequence(cps);
        }
        return isPictographic(first) && cps[cps.length - 1] != ZWJ && isZwjSequence(cps);
    }

    private static boolean isFlag(int[] cps) {
        return cps.length == 2 && isRegionalIndicator(cps[1]);
    }

    private static boolean isKeycap(int[] cps) {
        return (cps.length == 2 && cps[1] == KEYCAP) || (cps.length == 3 && cps[1] == VS16 && cps[2] == KEYCAP);
    }

    private static boolean isZwjSequence(int[] cps) {
        for (int i = 1; i < cps.length; i++) {
            int cp = cps[i];
            boolean part = isPictographic(cp) || Character.isEmojiModifier(cp) || cp == VS16;
            boolean joiner = cp == ZWJ && isPictographic(cps[i + 1 < cps.length ? i + 1 : i]);
            if (!part && !joiner) {
                return false;
            }
        }
        return true;
    }

    // 🏴 + [E0020-E007E]+ + E007F
    private static boolean isTagSequence(int[] cps) {
        if (cps.length < 3 || cps[cps.length - 1] != 0xE007F) {
            return false;
        }
        for (int i = 1; i < cps.length - 1; i++) {
            if (cps[i] < 0xE0020 || cps[i] > 0xE007E) {
                return false;
            }
        }
        return true;
    }

    // Extended_Pictographic은 미래 이모지용 미할당 영역까지 포함하므로 실제 할당된 코드포인트만 이모지로 본다.
    // ©·®·™처럼 텍스트 기본 표시인 기호도 이 속성에 들어 있어 허용된다(정책상 "이모지"로 취급)
    private static boolean isPictographic(int cp) {
        return Character.isExtendedPictographic(cp) && Character.isDefined(cp);
    }

    private static boolean isHangul(int cp) {
        return cp >= 0xAC00 && cp <= 0xD7A3;
    }

    private static boolean isAsciiLetterOrDigit(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
    }

    private static boolean isKeycapBase(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isTag(int cp) {
        return cp >= 0xE0020 && cp <= 0xE007F;
    }
}
