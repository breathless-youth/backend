package project.study.metrics.report.render;

import static project.study.metrics.slack.SlackMessage.bold;

import java.util.List;
import java.util.Map;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 🧑‍🎓 어제 공부한 유저 스레드 (부록 A 스레드 6). 단계 기준 문장을 첫머리에 그대로 쓰고, 사람은 두 줄(항목·하위 항목)로 쓴다. */
public final class ActiveUsersRenderer {

    static final int ROW_LIMIT = 100;

    private static final Map<Stage, String> ICONS =
            Map.of(Stage.NEW, "🌱", Stage.LOVERS, "🔥", Stage.COMEBACK, "🔄", Stage.SOMETIMES, "🌙");
    private static final Map<Stage, String> CRITERIA = Map.of(
            Stage.NEW, "어제가 가입 첫 주(가입일 포함 7일) 안",
            Stage.LOVERS, "최근 7일(어제 포함) 중 3일 이상 공부",
            Stage.COMEBACK, "첫 주가 지났고, 어제 전 7일 동안 공부 안 하다가 어제 공부 (가입 뒤 처음 공부한 사람 포함)",
            Stage.SOMETIMES, "나머지 (최근 7일 중 1~2일 공부)");

    private ActiveUsersRenderer() {}

    public static SlackMessage render(ActiveUsersSection section) {
        Builder out;
        if (section.total() == 0) {
            out = SlackMessage.builder("🧑‍🎓 어제 공부한 사람 없음");
        } else {
            out = SlackMessage.builder("🧑‍🎓 어제 공부한 %d명".formatted(section.total()));
            out.item("단계 기준 (New가 먼저)");
            for (Stage stage : Stage.values()) {
                out.sub(stage.label() + " = " + CRITERIA.get(stage));
            }
            stages(out, section);
            out.blank();
        }
        String appOnly = section.appOnly() == null ? "Amplitude 미수집" : section.appOnly() + "명";
        return out.item("앱만 열고 공부 안 함 = " + appOnly).build();
    }

    // 메시지 전체 100명 상한 — 넘는 사람은 "외 N명"으로 (스펙 §4.6)
    private static void stages(Builder out, ActiveUsersSection section) {
        int printed = 0;
        for (Stage stage : Stage.values()) {
            List<ActiveUserRow> rows = section.byStage().getOrDefault(stage, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            out.blank().heading(ICONS.get(stage) + " " + stage.label() + " " + rows.size());
            for (ActiveUserRow row : rows.subList(0, Math.min(rows.size(), ROW_LIMIT - printed))) {
                row(out, row);
                printed++;
            }
        }
        if (printed < section.total()) {
            out.item("⋯ 외 %d명".formatted(section.total() - printed));
        }
    }

    private static void row(Builder out, ActiveUserRow row) {
        String joined = row.daysSinceSignup() == 0 ? "어제 가입" : "가입 " + row.daysSinceSignup() + "일 뒤";
        String app = row.appDays7() == null ? "" : " · 최근 7일 앱 실행 " + row.appDays7() + "일";
        out.item(
                        bold(row.nickname()),
                        " (#%d) · %s · 어제 순공 ".formatted(row.userId(), joined),
                        bold(Fmt.duration(row.focusSec())),
                        " · 연속 " + Fmt.streak(row.streak()))
                .sub("%s 위주 · 과목 %d개 · %s%s"
                        .formatted(row.socialMostly() ? "소셜" : "싱글", row.liveSubjects(), row.profile(), app));
    }
}
