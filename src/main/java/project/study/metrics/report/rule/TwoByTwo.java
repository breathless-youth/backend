package project.study.metrics.report.rule;

import java.util.List;
import java.util.function.Predicate;
import project.study.metrics.report.Rate;
import project.study.metrics.report.UserTimeline;

/**
 * 규칙 하나의 2×2 표: a = 함·남음, b = 함·안 남음, c = 안 함·남음, d = 안 함·안 남음 (스펙 §6).
 */
public record TwoByTwo(int a, int b, int c, int d) {

    public static final int MIN_SIDE = 5;
    public static final int MAX_DOER_PERCENT = 90;
    public static final double MIN_PHI = 0.2;

    public static TwoByTwo of(
            List<UserTimeline> cohort, Predicate<UserTimeline> did, Predicate<UserTimeline> retained) {
        int[] cells = new int[4];
        for (UserTimeline user : cohort) {
            int row = did.test(user) ? 0 : 2;
            int column = retained.test(user) ? 0 : 1;
            cells[row + column]++;
        }
        return new TwoByTwo(cells[0], cells[1], cells[2], cells[3]);
    }

    public int doers() {
        return a + b;
    }

    public int nonDoers() {
        return c + d;
    }

    public int total() {
        return a + b + c + d;
    }

    public int retained() {
        return a + c;
    }

    public Rate doerRetention() {
        return Rate.of(a, doers());
    }

    public Rate nonDoerRetention() {
        return Rate.of(c, nonDoers());
    }

    /** phi = (ad − bc) / √((a+b)(c+d)(a+c)(b+d)). 분모가 0이면 NaN. */
    public double phi() {
        double denominator = Math.sqrt((double) doers() * nonDoers() * retained() * (b + d));
        return denominator == 0 ? Double.NaN : ((double) a * d - (double) b * c) / denominator;
    }

    public Exposure exposure() {
        if (doers() < MIN_SIDE || nonDoers() < MIN_SIDE) {
            return Exposure.TOO_FEW;
        }
        if (doers() * 100L > (long) total() * MAX_DOER_PERCENT) {
            return Exposure.TOO_COMMON;
        }
        double phi = phi();
        return Double.isNaN(phi) || phi < MIN_PHI ? Exposure.WEAK : Exposure.PASS;
    }
}
