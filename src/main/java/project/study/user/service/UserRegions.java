package project.study.user.service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 기기 지역(ISO 3166-1 두 글자)의 해석.
 *
 * 앱이 보낸 값을 대문자로 맞춰 국가 코드 목록에 있으면 쓰고, 아니면 거절하지 않고 무시한다(시간대와 같은 방식).
 * 지역 때문에 프로필 저장이 실패하면 함께 보낸 다른 값까지 잃는다.
 */
public final class UserRegions {

    private static final Set<String> COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    private UserRegions() {}

    /** 읽을 수 있는 국가 코드면 대문자 두 글자, 아니면 empty. */
    public static Optional<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        return COUNTRIES.contains(code) ? Optional.of(code) : Optional.empty();
    }
}
