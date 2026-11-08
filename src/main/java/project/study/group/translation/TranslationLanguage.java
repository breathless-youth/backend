package project.study.group.translation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 앱 locale과 무료 표준 번역 모델의 코드. 홍콩은 표준 번체이며 광둥어로 추측하지 않는다. */
public enum TranslationLanguage {
    KO("ko", "ko", "ko", "KO"),
    EN("en", "en", "en", "EN-US"),
    JA("ja", "ja", "ja", "JA"),
    ZH_TW("zh-TW", "zh-TW", "zh-Hant", "ZH-HANT");

    private final String locale;
    private final String google;
    private final String azure;
    private final String deepL;
    // Locale.LanguageRange는 지수 q값과 토큰 내부 공백도 허용해 HTTP 문법을 먼저 검사한다.
    private static final Pattern HTTP_RANGE = Pattern.compile(
            "[ \\t]*(?:[a-z]{1,8}(?:-[a-z0-9]{1,8})*|\\*)(?:[ \\t]*;[ \\t]*q=(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?))?[ \\t]*",
            Pattern.CASE_INSENSITIVE);

    TranslationLanguage(String locale, String google, String azure, String deepL) {
        this.locale = locale;
        this.google = google;
        this.azure = azure;
        this.deepL = deepL;
    }

    public String locale() {
        return locale;
    }

    public String providerCode(TranslationProvider provider) {
        return switch (provider) {
            case GOOGLE -> google;
            case AZURE -> azure;
            case DEEPL -> deepL;
        };
    }

    public static Optional<TranslationLanguage> resolve(String header) {
        if (header == null || header.isBlank() || header.length() > 1024) return Optional.empty();
        if (!Arrays.stream(header.split(",", -1))
                .allMatch(range -> HTTP_RANGE.matcher(range).matches())) return Optional.empty();
        try {
            return Locale.LanguageRange.parse(header).stream()
                    .filter(range -> range.getWeight() > 0)
                    .map(range -> fromRange(range.getRange()))
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<TranslationLanguage> fromRange(String range) {
        Locale locale = Locale.forLanguageTag(range);
        if (locale.getLanguage().equals("zh")) {
            boolean traditionalScript = locale.getScript().equals("Hant");
            boolean traditionalRegion = locale.getScript().isEmpty()
                    && java.util.Set.of("TW", "HK", "MO").contains(locale.getCountry());
            return traditionalScript || traditionalRegion ? Optional.of(ZH_TW) : Optional.empty();
        }
        return westernLanguage(range);
    }

    private static Optional<TranslationLanguage> westernLanguage(String range) {
        String base = range.split("-", 2)[0];
        return switch (base) {
            case "ko" -> Optional.of(KO);
            case "en" -> Optional.of(EN);
            case "ja" -> Optional.of(JA);
            default -> Optional.empty();
        };
    }
}
