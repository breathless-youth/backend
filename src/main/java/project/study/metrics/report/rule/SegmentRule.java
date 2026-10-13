package project.study.metrics.report.rule;

import project.study.metrics.report.UserTimeline;

/**
 * 행동 규칙 하나. 이름은 기간 접두어 + 문구로 만든다 — 메시지 설명과 판정이 어긋나지 않게 같은 파라미터에서 나온다(스펙 §5).
 *
 * @param phrase "주로 한 행동" 목록에 쓰는 짧은 이름
 */
public record SegmentRule(
        String key, RuleGroup group, RuleSource source, Period period, String phrase, Matcher matcher) {

    public enum Period {
        FIRST_WEEK("첫 주에 "),
        SIGNUP_DAY("가입한 날 "),
        FIRST_TWO_DAYS("가입 이틀 안에 ");

        private final String prefix;

        Period(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    @FunctionalInterface
    public interface Matcher {
        boolean test(UserTimeline user, CohortStats stats);
    }

    public String name() {
        return period.prefix() + phrase;
    }

    public boolean matches(UserTimeline user, CohortStats stats) {
        return matcher.test(user, stats);
    }

    public boolean amplitude() {
        return source == RuleSource.AMPLITUDE;
    }
}
