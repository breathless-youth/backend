package project.study.common.text;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 눈에 보이는 글자 단위(확장 자소 클러스터, grapheme) 도구. NFD 한글·ZWJ 이모지·국기를 한 글자로 센다.
 *
 * <p>입력이 매우 길면 비용이 커지므로, 사용자 입력은 먼저 원문 길이로 막은 뒤 부른다({@link TextLength} 참고).
 */
public final class Graphemes {

    // 공백류(\p{Z})·서식 문자(\p{Cf}, 제로폭 공백·ZWJ 포함)·제어 문자(\p{Cc})에 더해, 글자로 분류되지만(Lo) 화면에 아무것도
    // 그리지 않는 한글 채움 문자(U+3164·U+115F·U+1160·U+FFA0). strip()은 이 중 일부(NBSP·제로폭·채움 문자)를 못 지운다.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\p{Cf}\\p{Cc}\\u3164\\u115F\\u1160\\uFFA0]");

    private Graphemes() {}

    /** NFC 합성 + 앞뒤 공백 제거. null은 null(부분 수정에서 미변경). */
    public static String normalize(String raw) {
        return raw == null
                ? null
                : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
    }

    /** 글자 단위로 나눈다. 빈 문자열이면 빈 목록. */
    public static List<String> split(String text) {
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

    /**
     * 보이지 않는 글자만으로 이루어졌는가(빈 문자열 포함). 이름이 비어 보이는데 {@code min = 1}을 통과하는 걸 막으려고 {@link #normalize}한 값에
     * 쓴다. ZWJ 이모지 조합처럼 보이는 글자가 하나라도 있으면 거짓이다.
     */
    public static boolean isVisuallyBlank(String normalized) {
        return INVISIBLE.matcher(normalized).replaceAll("").isEmpty();
    }

    /** 글자 단위 개수. */
    public static int count(String text) {
        return split(text).size();
    }

    /** 첫 글자 단위. 이모지가 앞에 와도 서로게이트 절반이 잘리지 않는다. 빈 문자열이면 빈 문자열. */
    public static String first(String text) {
        List<String> graphemes = split(text);
        return graphemes.isEmpty() ? "" : graphemes.get(0);
    }
}
