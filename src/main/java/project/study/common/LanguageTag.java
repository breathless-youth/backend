package project.study.common;

import java.util.Locale;

/** Accept-Language에서 서버가 가진 이름 번역의 키를 고른다. */
public final class LanguageTag {

    public static final String DEFAULT = "ko";

    private LanguageTag() {}

    /**
     * 첫 언어 태그의 주 언어만 소문자로 준다(예: {@code ja-JP,ja;q=0.9} → {@code ja}, {@code zh-Hant-TW} → {@code zh}). 헤더가 없거나 읽을 수
     * 없으면 한국어다.
     */
    public static String primary(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT;
        }
        String first = acceptLanguage.split(",")[0].split(";")[0].trim();
        String primary = first.split("[-_]")[0].toLowerCase(Locale.ROOT);
        return primary.matches("[a-z]{2,3}") ? primary : DEFAULT;
    }
}
