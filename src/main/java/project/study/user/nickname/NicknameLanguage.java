package project.study.user.nickname;

import java.util.Locale;

/**
 * 기본 닉네임 목록의 언어 (BY-1135). 가입할 때 앱이 보낸 휴대폰 언어 태그로 고른다.
 *
 * <p>인도와 목록이 없는 언어는 영어로 간다. 간체(`Hans`)는 지역과 상관없이 간체 목록, 홍콩·마카오와 광둥어(`yue`)는 광둥어 구어체 목록,
 * 대만과 지역 없는 번체는 대만 목록, 그 밖의 중국어는 간체 목록이다.
 */
public enum NicknameLanguage {
    KO("ko", " "),
    EN("en", " "),
    JA("ja", ""),
    ZH_CN("zh-CN", ""),
    ZH_TW("zh-TW", ""),
    ZH_HK("zh-HK", "");

    private final String code;
    private final String joiner;

    NicknameLanguage(String code, String joiner) {
        this.code = code;
        this.joiner = joiner;
    }

    /** 리소스 폴더 이름(`nickname/auto/<code>/`). */
    public String code() {
        return code;
    }

    /** 꾸미는 말과 동물 사이에 넣는 문자열 — 한국어·영어는 띄어 쓰고, 일본어·중국어는 붙여 쓴다. */
    public String joiner() {
        return joiner;
    }

    /** BCP 47 태그(대소문자·밑줄 구분자 허용) → 목록. 태그가 없으면 옛 앱이라 한국어, 읽을 수 없거나 목록이 없는 언어면 영어. */
    public static NicknameLanguage fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return KO;
        }
        Locale locale = Locale.forLanguageTag(tag.strip().replace('_', '-'));
        return switch (locale.getLanguage()) {
            case "ko" -> KO;
            case "ja" -> JA;
            case "zh" -> chinese(locale);
            case "yue" -> ZH_HK; // 광둥어(iOS "粵語（香港）")
            default -> EN;
        };
    }

    // 간체를 지역보다 먼저 본다 — 홍콩·대만에 사는 간체 사용자는 간체 목록이다
    private static NicknameLanguage chinese(Locale locale) {
        if (locale.getScript().equals("Hans")) {
            return ZH_CN;
        }
        String region = locale.getCountry();
        if (region.equals("HK") || region.equals("MO")) {
            return ZH_HK;
        }
        if (region.equals("TW") || (region.isEmpty() && locale.getScript().equals("Hant"))) {
            return ZH_TW;
        }
        return ZH_CN;
    }
}
