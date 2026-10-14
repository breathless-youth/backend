package project.study.metrics.report.render;

import static project.study.metrics.slack.SlackMessage.bold;

import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 📈 리텐션 스레드 (부록 A 스레드 2). */
public final class RetentionRenderer {

    private RetentionRenderer() {}

    public static SlackMessage render(RetentionSection section) {
        Builder out = SlackMessage.builder("📈 다시 와서 공부한 비율")
                .item("기준")
                .sub("공부 = 그날 10분 이상 공부한 날")
                .sub("구간이 다 끝난 최근 28일치 가입자만 셈");
        for (RetentionRow row : section.rows()) {
            out.item(label(row.window()) + " = ", bold(Fmt.percent(row.current())), Fmt.fraction(row.current()));
            out.sub("지난주 %s · 가입 %s"
                    .formatted(Fmt.percent(row.lastWeek()), Fmt.range(row.cohortFrom(), row.cohortTo())));
            if (row.window() == Window.WEEK_TWO) {
                out.sub("7일 중 하루라도 공부하면 셈. 6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)");
            }
        }
        return out.item(
                        "첫 주에 한 번도 공부 안 한 사람 = ",
                        bold(Fmt.percent(section.firstWeekNone())),
                        Fmt.fraction(section.firstWeekNone()))
                .sub("가입 " + Fmt.range(section.noneFrom(), section.noneTo()))
                .build();
    }

    private static String label(Window window) {
        return switch (window) {
            case NEXT_DAY -> "가입 다음 날";
            case WEEK_TWO -> "7~13일 뒤";
            case WEEK_FIVE -> "28~34일 뒤";
        };
    }
}
