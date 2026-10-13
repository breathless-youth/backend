package project.study.metrics.report;

/** 분자/분모. 메시지에는 "57% (8/14)"로, 분모가 0이면 "—"로 쓴다. */
public record Rate(int numerator, int denominator) {

    public static Rate of(int numerator, int denominator) {
        return new Rate(numerator, denominator);
    }

    public boolean defined() {
        return denominator > 0;
    }

    /** 정수 반올림(half-up). */
    public int percent() {
        if (!defined()) {
            throw new IllegalStateException("분모가 0인 비율에는 퍼센트가 없다");
        }
        return (int) Math.round(100.0 * numerator / denominator);
    }

    public double ratio() {
        return defined() ? (double) numerator / denominator : 0;
    }

    /** 실수 오차 없이 "percent% 이상"을 비교한다. */
    public boolean atLeastPercent(int percent) {
        return defined() && numerator * 100L >= (long) percent * denominator;
    }
}
