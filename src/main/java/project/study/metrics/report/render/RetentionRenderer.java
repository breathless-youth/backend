package project.study.metrics.report.render;

import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

/** 📈 리텐션 스레드 (부록 A 스레드 2). */
public final class RetentionRenderer {

    private static final int LABEL_WIDTH = 13;
    private static final int PERCENT_WIDTH = 4;
    private static final int FRACTION_WIDTH = 10;

    private RetentionRenderer() {}

    public static String render(RetentionSection section) {
        return String.join(
                "\n",
                "📈 다시 와서 공부한 비율",
                "공부 = 그날 10분 이상 공부한 날 · 구간이 다 끝난 최근 28일치 가입자만 셈",
                Fmt.code(section.rows().stream().map(RetentionRenderer::row).toList()),
                "→ 7~13일 뒤 = 가입 7일 뒤부터 13일 뒤까지 7일 중 하루라도 공부한 사람.",
                "  6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)",
                "",
                "첫 주에 한 번도 공부 안 한 사람: %s [가입 %s]"
                        .formatted(Fmt.rate(section.firstWeekNone()), Fmt.range(section.noneFrom(), section.noneTo())));
    }

    private static String row(RetentionRow row) {
        String fraction = row.current().defined()
                ? "(%d/%d)".formatted(row.current().numerator(), row.current().denominator())
                : "";
        return Fmt.padRight(label(row.window()), LABEL_WIDTH)
                + Fmt.padLeft(Fmt.percent(row.current()), PERCENT_WIDTH)
                + " "
                + Fmt.padRight(fraction, FRACTION_WIDTH)
                + " 지난주 "
                + Fmt.padLeft(Fmt.percent(row.lastWeek()), PERCENT_WIDTH)
                + "   [가입 "
                + Fmt.range(row.cohortFrom(), row.cohortTo())
                + "]";
    }

    private static String label(Window window) {
        return switch (window) {
            case NEXT_DAY -> "가입 다음 날";
            case WEEK_TWO -> "7~13일 뒤";
            case WEEK_FIVE -> "28~34일 뒤";
        };
    }
}
