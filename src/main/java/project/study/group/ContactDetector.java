package project.study.group;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 연락처·링크 판정. 그룹 이름·소개, 신청 소개 글에 외부 연락 수단이 들어 있는지 본다 (BY-1124).
 *
 * <p>서버가 기준이고 FE가 같은 규칙을 따라 한다. 짧은 숫자(9급, 토익 900, D-100, 2027 수능)는 통과시킨다.
 */
public final class ContactDetector {

    /**
     * 숫자 사이에 구분자({@code -}·공백·{@code .}) 하나까지만 끼워도 이어진 번호로 보고, 이 개수 이상이면 막는다. 구분자가 연달아 오면
     * (예: 기간 표기 {@code 2026-11-03 - 2027-02-01}의 {@code " - "}) 숫자열이 끊긴다.
     */
    private static final int PHONE_MIN_DIGITS = 9;

    private static final String SEPARATOR = "[\\s:：-]{0,3}";

    // 아래 정규식은 NFKC로 바꾸고 소문자로 낮춘 글에 적용한다.
    private static final Pattern LINK = Pattern.compile("https?://|www\\.");

    // 최상위 도메인 허용 목록이라 node.js 같은 건 통과한다. 앞 글자는 한글 도메인도 잡도록 글자·숫자를 다 받는다.
    private static final Pattern DOMAIN = Pattern.compile("[\\p{L}\\p{N}-]+\\."
            + "(?:com|net|org|kr|co|io|me|ly|gg|so|app|dev|link|site|xyz|info|biz|shop)(?![a-z0-9])");

    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");

    private static final Pattern PHONE_LIKE = Pattern.compile("\\d(?:[ .-]?\\d){" + (PHONE_MIN_DIGITS - 1) + ",}");

    private static final Pattern AT_ID = Pattern.compile("@[a-z0-9._]{2,}");

    // 연락 수단 말 바로 뒤(구분자 0~3자)에 아이디처럼 보이는 토큰(3자 이상, 영문자 하나 이상)이 오면 막는다.
    // "카톡 말고 …"는 한글이라, "카톡 100명 모임"·"라인 100선"은 숫자뿐이라 통과한다(숫자만 9자리 이상이면 숫자 규칙이 막는다).
    // dm은 admin 같은 영어 단어 속, 라인은 가이드라인 같은 한글 단어 속에서는 연락 수단으로 보지 않는다.
    private static final Pattern CONTACT_WORD_WITH_ID =
            Pattern.compile("(?:카카오톡|카톡|오픈채팅|오픈톡|인스타그램|인스타|텔레그램|텔레|디엠|(?<![a-z0-9])dm|(?<![가-힣])라인|디코|디스코드)"
                    + SEPARATOR
                    + "(?:(?:아이디|id)" + SEPARATOR + ")?"
                    + "(?=[a-z0-9._]*[a-z])[a-z0-9._]{3,}");

    private ContactDetector() {}

    /** 연락처·링크가 들어 있으면 true. null·빈 문자열은 false. */
    public static boolean containsContact(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return LINK.matcher(normalized).find()
                || DOMAIN.matcher(normalized).find()
                || EMAIL.matcher(normalized).find()
                || PHONE_LIKE.matcher(normalized).find()
                || AT_ID.matcher(normalized).find()
                || CONTACT_WORD_WITH_ID.matcher(normalized).find();
    }
}
