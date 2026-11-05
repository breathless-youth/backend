package project.study.group.service;

/**
 * 그룹 이름을 같은 이름인지 가르는 키 (BY-1124). 공백을 모두 지우고 소문자로 맞춘다.
 *
 * <p>이름 락과 중복 검사가 같은 식을 써야 하고, 판정은 DB 안에서 한다. 자바에서 따로 계산하면 두 곳의 공백·대소문자 규칙이 어긋나 락은
 * 같은데 검사는 통과하는 이름이 생긴다.
 */
public final class GroupNames {

    private GroupNames() {}

    /** SQL에서 이름 하나({@code expression})를 키로 바꾸는 식. */
    public static String keySql(String expression) {
        return "lower(regexp_replace(" + expression + ", '\\s+', '', 'g'))";
    }
}
