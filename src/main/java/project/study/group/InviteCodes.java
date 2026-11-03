package project.study.group;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * 비공개 그룹 초대코드 (BY-1124). 헷갈리는 0·O·1·I·L을 뺀 31자에서 6자리를 뽑는다(31^6 ≈ 8.9억). 대소문자는 가리지 않아 비교하기 전에
 * {@link #canonical}로 대문자로 맞춘다.
 *
 * <p>같은 코드가 동시에 발급되는 경우는 {@code study_group.invite_code}의 부분 unique 인덱스가 마지막으로 막는다.
 */
public final class InviteCodes {

    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    static final int LENGTH = 6;

    // 31^6 중 쓰는 코드는 극히 일부라 한두 번이면 비는 코드가 나온다. 10번을 넘게 겹치면 난수나 데이터가 이상한 것이다
    static final int MAX_ATTEMPTS = 10;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private InviteCodes() {}

    public static String generate(RandomGenerator random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** 사용자가 입력한 코드를 저장된 모양(앞뒤 공백 없는 대문자)으로 맞춘다. null은 null. */
    public static String canonical(String raw) {
        return raw == null ? null : raw.strip().toUpperCase(Locale.ROOT);
    }

    /** 쓰이지 않는 코드를 {@link SecureRandom}으로 뽑는다. */
    public static String issue(Predicate<String> taken) {
        return issue(SECURE_RANDOM, taken);
    }

    /** 이미 쓰는 코드({@code taken})면 다시 뽑는다. {@value #MAX_ATTEMPTS}번을 넘게 겹치면 {@link IllegalStateException}. */
    static String issue(RandomGenerator random, Predicate<String> taken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generate(random);
            if (!taken.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("초대코드를 " + MAX_ATTEMPTS + "번 뽑았지만 모두 이미 쓰는 코드였다");
    }
}
