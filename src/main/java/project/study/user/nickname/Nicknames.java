package project.study.user.nickname;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 닉네임 규칙 (BY-647): 한글·영문·숫자·이모지·띄어쓰기만 허용, 앞뒤 공백을 잘라낸 뒤 글자 단위(확장 자소 클러스터, grapheme)로 2~12자.
 *
 * <p>허용 목록 방식이라 문장부호·다른 언어·보이지 않는 문자(제로폭·제어·서식 문자)는 지우지 않고 거부한다 — 보이지 않는
 * 문자가 섞인 닉네임이 저장될 여지를 줄인다(이모지 부품 조합의 표시 차이까지 보장하지는 않는다). 이모지는 조합(ZWJ 시퀀스, 피부색, 국기, 키캡, 🏴 태그)을
 * 한 글자로 세되 구성 요소가 전부 이모지 부품일 때만 인정한다. NFC로 합성해 자모로 입력된 한글도 완성형으로 본다.
 *
 * <p>글자 수만으로는 ZWJ 사슬을 막지 못하므로 원문 길이와 코드포인트 수에도 상한을 둔다.
 */
public final class Nicknames {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 12;
    /** 정규화 전 원문 길이(UTF-16 단위) 상한 — 정규화 비용 자체를 막는 빠른 검사. */
    public static final int MAX_RAW_CHARS = 256;
    /** 정규화 후 코드포인트 상한 — 가족 이모지(7)·지역 깃발(7) 12개도 넉넉히 들어간다. */
    public static final int MAX_CODE_POINTS = 100;

    private static final int SPACE = ' ';
    private static final int ZWJ = 0x200D;
    private static final int VS16 = 0xFE0F;
    private static final int KEYCAP = 0x20E3;
    private static final int BLACK_FLAG = 0x1F3F4;

    private Nicknames() {}

    /** NFC 합성 + 앞뒤 공백 제거. null은 null(부분 수정에서 미변경). 저장 직전에도 같은 정규화를 적용한다. */
    public static String normalize(String raw) {
        return raw == null
                ? null
                : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
    }

    /** 원문 상한 → 정규화 → 코드포인트 상한 → 글자 단위 2~12자 → 모든 글자가 허용 목록 안. */
    public static boolean isAcceptable(String raw) {
        if (raw.length() > MAX_RAW_CHARS) {
            return false;
        }
        String normalized = normalize(raw);
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            return false;
        }
        List<String> graphemes = graphemes(normalized);
        if (graphemes.size() < MIN_LENGTH || graphemes.size() > MAX_LENGTH) {
            return false;
        }
        return graphemes.stream().allMatch(Nicknames::isAllowed);
    }

    /** 눈에 보이는 글자 단위(grapheme) 개수. */
    public static int length(String nickname) {
        return graphemes(nickname).size();
    }

    /** 아바타 이니셜 — 첫 글자 단위. 이모지가 앞에 와도 서로게이트 절반이 잘리지 않는다. */
    public static String initialOf(String nickname) {
        List<String> graphemes = graphemes(nickname);
        return graphemes.isEmpty() ? "" : graphemes.get(0);
    }

    private static List<String> graphemes(String text) {
        List<String> result = new ArrayList<>();
        if (text.isEmpty()) {
            return result;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            result.add(text.substring(start, end));
        }
        return result;
    }

    // 한 글자 단위가 허용 목록 안인가 — 단일 코드포인트면 한글·영문·숫자·스페이스·그림 이모지, 여러 개면 이모지 조합
    private static boolean isAllowed(String grapheme) {
        int[] cps = grapheme.codePoints().toArray();
        if (cps.length == 1) {
            int cp = cps[0];
            return cp == SPACE || isHangul(cp) || isAsciiLetterOrDigit(cp) || isPictographic(cp);
        }
        return isEmojiSequence(cps);
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
