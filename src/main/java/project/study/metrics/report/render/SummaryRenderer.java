package project.study.metrics.report.render;

import io.sentry.Sentry;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.Kst;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SignupSection;

/**
 * 📊 본 메시지(요약) — 매일 훑어보는 줄이라 용어 없이 풀어 쓴다(스펙 §4.7). 정의·분모·코호트 기간은 스레드에 둔다.
 */
@Slf4j
public final class SummaryRenderer {

    private SummaryRenderer() {}

    public static String render(DailyReport report) {
        return String.join(
                "\n",
                List.of(
                        "📊 " + Fmt.monthDayWeekday(report.anchor()) + " 일일 지표",
                        line(report.signup(), "가입", SummaryRenderer::signup),
                        line(report.retention(), "다시 와서 공부한 비율", SummaryRenderer::retention),
                        line(report.segment(), "첫 주 3일 이상 공부한 사람", SummaryRenderer::segment),
                        line(report.explorer(), "남는 사람과 관련 깊은 행동", SummaryRenderer::explorer),
                        line(report.active(), "어제 공부한 사람", SummaryRenderer::active)));
    }

    private static <T> String line(SectionResult<T> result, String label, Function<T, String> renderer) {
        String failed = "• " + label + ": 계산 실패";
        try {
            return result.value().map(value -> "• " + renderer.apply(value)).orElse(failed);
        } catch (RuntimeException e) {
            log.error("일일 리포트 요약 줄 렌더링 실패 (줄={})", label, e);
            Sentry.captureException(e);
            return failed;
        }
    }

    private static String signup(SignupSection section) {
        String count = "가입 %d명".formatted(section.yesterdayCount());
        return section.peak()
                .map(band -> count + " · 가장 많이 가입한 시간 " + Kst.bandLabel(band.startHour()))
                .orElse(count);
    }

    private static String retention(RetentionSection section) {
        var rows = section.rows();
        return "다시 와서 공부한 비율: 가입 다음 날 %s · 7~13일 뒤 %s · 28~34일 뒤 %s  (지난주 %s · %s · %s)"
                .formatted(
                        Fmt.percent(rows.get(0).current()),
                        Fmt.percent(rows.get(1).current()),
                        Fmt.percent(rows.get(2).current()),
                        Fmt.percent(rows.get(0).lastWeek()),
                        Fmt.percent(rows.get(1).lastWeek()),
                        Fmt.percent(rows.get(2).lastWeek()));
    }

    private static String segment(SegmentSection section) {
        GroupRow three = section.groups().get(0);
        if (three.size() == 0) {
            return "첫 주에 3일 이상 공부한 사람 없음 (가입 %s %d명 중)"
                    .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize());
        }
        return "첫 주에 3일 이상 공부한 %d명 중 %d명(%s)이 둘째 주에도 공부 — 1~2일 공부한 사람은 %s, 안 한 사람은 %s (명단은 스레드)"
                .formatted(
                        three.size(),
                        three.retained().numerator(),
                        Fmt.percent(three.retained()),
                        Fmt.percent(section.groups().get(1).retained()),
                        Fmt.percent(section.groups().get(2).retained()));
    }

    private static String explorer(ExplorerSection section) {
        if (section.top().isEmpty()) {
            return "남는 사람과 관련 깊은 행동: 오늘은 조건을 통과한 규칙 없음 (표본 부족)";
        }
        RankedRule first = section.top().getFirst();
        return "남는 사람과 가장 관련 깊은 행동: %s%s (연관 %s, %s)"
                .formatted(
                        first.rule().name(),
                        first.fixed() ? " (고정 세그먼트)" : "",
                        first.strength().label(),
                        Fmt.trend(first));
    }

    private static String active(ActiveUsersSection section) {
        if (section.total() == 0) {
            return "어제 공부한 사람 없음";
        }
        return ("어제 공부한 %d명: New %d (가입 첫 주) · Lovers %d (최근 7일 중 3일+) · Comeback %d (1주 쉬다 옴)"
                        + " · Sometimes %d (최근 7일 중 1~2일)")
                .formatted(
                        section.total(),
                        section.count(Stage.NEW),
                        section.count(Stage.LOVERS),
                        section.count(Stage.COMEBACK),
                        section.count(Stage.SOMETIMES));
    }
}
