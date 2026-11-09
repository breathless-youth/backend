package project.study.group.translation;

/**
 * 원문이 이미 요청 언어로 읽히는지 문자 체계만으로 판단한다.
 *
 * <p>공급자의 언어 감지는 문자를 보낸 뒤에야 알 수 있어 무료량을 쓴다. 그래서 확실한 경우만 건너뛴다.
 * 한글이 글자의 과반이고 가나가 없으면 한국어, 가나가 있고 가나·한자가 과반이며 한글이 없으면 일본어,
 * 글자가 모두 기본 라틴(A-Z)이고 비ASCII 결합 부호가 없으면 영어로 본다.
 * 한자만 있는 글은 일본어인지 정할 수 없고, 번체 대상은 간체 원문을 바꿔 줄 수 있어 항상 보낸다.
 * 글자가 하나도 없으면(숫자·이모지·기호뿐) 어느 언어로도 바꿀 것이 없다.
 */
final class SourceLanguage {
    private SourceLanguage() {}

    static boolean needsNoTranslation(String text, TranslationLanguage target) {
        Letters letters = Letters.count(text);
        if (letters.total() == 0) return true;
        return switch (target) {
            case KO -> letters.kana() == 0 && letters.hangul() * 2 > letters.total();
            case JA ->
                letters.hangul() == 0 && letters.kana() > 0 && (letters.kana() + letters.han()) * 2 > letters.total();
            case EN -> letters.ascii() == letters.total() && !letters.combiningMark();
            case ZH_TW -> false;
        };
    }

    private record Letters(int total, int hangul, int kana, int han, int ascii, boolean combiningMark) {
        static Letters count(String text) {
            int total = 0;
            int hangul = 0;
            int kana = 0;
            int han = 0;
            int ascii = 0;
            boolean combiningMark = false;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                combiningMark |= isCombiningMark(codePoint);
                if (!Character.isLetter(codePoint)) continue;
                total++;
                switch (Character.UnicodeScript.of(codePoint)) {
                    case HANGUL -> hangul++;
                    case HIRAGANA, KATAKANA -> kana++;
                    case HAN -> han++;
                    default -> ascii += codePoint < 0x80 ? 1 : 0;
                }
            }
            return new Letters(total, hangul, kana, han, ascii, combiningMark);
        }

        /** `e` + U+0301처럼 악센트를 따로 붙인 글자. 이모지 변형 선택자는 글자 모양만 바꾸므로 제외한다. */
        private static boolean isCombiningMark(int codePoint) {
            boolean variationSelector =
                    (codePoint >= 0xFE00 && codePoint <= 0xFE0F) || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
            int type = Character.getType(codePoint);
            return !variationSelector
                    && (type == Character.NON_SPACING_MARK
                            || type == Character.COMBINING_SPACING_MARK
                            || type == Character.ENCLOSING_MARK);
        }
    }
}
