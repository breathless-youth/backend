package project.study.metrics.report.render;

import static project.study.metrics.slack.SlackMessage.bold;

import io.sentry.Sentry;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.Kst;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/**
 * 📊 본 메시지(요약) — "가입"·"리텐션" 묶음 아래 항목과 하위 항목으로 쓴다(BY-910). 용어 없이 풀어 쓰고, 정의·분모·코호트 기간은 스레드에 둔다
 * (스펙 §4.7).
 */
@Slf4j
public final class SummaryRenderer {

    private static final List<String> RETENTION_LABELS = List.of("가입 다음 날", "7~13일 뒤", "28~34일 뒤");
    private static final Map<Stage, String> STAGE_HINTS = Map.of(
            Stage.NEW, "가입 첫 주",
            Stage.LOVERS, "최근 7일 중 3일+",
            Stage.COMEBACK, "1주 쉬다 옴",
            Stage.SOMETIMES, "최근 7일 중 1~2일");

    private SummaryRenderer() {}

    public static SlackMessage render(DailyReport report) {
        Builder out = SlackMessage.builder("📊 " + Fmt.monthDayWeekday(report.anchor()) + " 일일 지표");
        out.heading("가입");
        part(out, report.signup(), "가입자 수", SummaryRenderer::signup);
        part(out, report.retention(), "다시 와서 공부한 비율", SummaryRenderer::retention);
        out.blank().heading("리텐션");
        part(out, report.segment(), "첫 주에 공부한 날별로 둘째 주에도 공부한 비율", SummaryRenderer::segment);
        part(out, report.active(), "어제 공부한 사람", SummaryRenderer::active);
        part(out, report.explorer(), "남는 사람과 가장 관련 깊은 행동", SummaryRenderer::explorer);
        return out.build();
    }

    // 묶음 하나를 따로 그려 붙인다 — 계산이나 그리기가 실패하면 그 항목만 "계산 실패"
    private static <T> void part(Builder out, SectionResult<T> result, String label, BiConsumer<Builder, T> writer) {
        try {
            T value = result.value().orElse(null);
            if (value == null) {
                out.item(label + " = 계산 실패");
                return;
            }
            out.addAll(SlackMessage.lines(lines -> writer.accept(lines, value)));
        } catch (RuntimeException e) {
            log.error("일일 리포트 요약 렌더링 실패 (항목={})", label, e);
            Sentry.captureException(e);
            out.item(label + " = 계산 실패");
        }
    }

    private static void signup(Builder out, SignupSection section) {
        out.item("가입자 수 = ", bold(section.yesterdayCount()), "명");
        section.peak().ifPresent(band -> out.item("가장 많이 가입한 시간 = ", bold(Kst.bandLabel(band.startHour()))));
    }

    private static void retention(Builder out, RetentionSection section) {
        out.item("다시 와서 공부한 비율");
        List<RetentionRow> rows = section.rows();
        for (int i = 0; i < rows.size(); i++) {
            out.sub(bold(Fmt.percent(rows.get(i).current())), " (" + RETENTION_LABELS.get(i) + ")");
        }
        out.quote("지난주 "
                + String.join(
                        " · ",
                        rows.stream().map(row -> Fmt.percent(row.lastWeek())).toList()));
    }

    private static void segment(Builder out, SegmentSection section) {
        if (section.groups().getFirst().size() == 0) {
            out.item("첫 주에 3일 이상 공부한 사람 없음 (가입 %s %d명 중)"
                    .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize()));
            return;
        }
        out.item("첫 주에 공부한 날별로 둘째 주에도 공부한 비율");
        for (GroupRow group : section.groups()) {
            out.sub(group.label() + " = ", bold(Fmt.percent(group.retained())), Fmt.fraction(group.retained()));
        }
        out.quote("명단은 스레드 참고");
    }

    private static void active(Builder out, ActiveUsersSection section) {
        if (section.total() == 0) {
            out.item("어제 공부한 사람 없음");
            return;
        }
        out.item("어제 공부한 ", bold(section.total()), "명 구성");
        for (Stage stage : Stage.values()) {
            out.sub(stage.label() + " (" + STAGE_HINTS.get(stage) + ") = ", bold(section.count(stage)));
        }
    }

    private static void explorer(Builder out, ExplorerSection section) {
        if (section.top().isEmpty()) {
            out.item("남는 사람과 가장 관련 깊은 행동 = 오늘은 조건을 통과한 규칙 없음 (표본 부족)");
            return;
        }
        RankedRule first = section.top().getFirst();
        out.item(
                "남는 사람과 가장 관련 깊은 행동 = ",
                bold(first.rule().name()),
                first.fixed() ? " (고정 세그먼트)" : "",
                " (연관 %s, %s)".formatted(first.strength().label(), Fmt.trend(first)));
    }
}
