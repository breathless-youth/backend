package project.study.common.text;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 눈에 보이는 글자 단위(확장 자소 클러스터, grapheme) 도구. NFD 한글·ZWJ 이모지·국기를 한 글자로 센다.
 *
 * <p>입력이 매우 길면 비용이 커지므로, 사용자 입력은 먼저 원문 길이로 막은 뒤 부른다({@link TextLength} 참고).
 */
public final class Graphemes {

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
