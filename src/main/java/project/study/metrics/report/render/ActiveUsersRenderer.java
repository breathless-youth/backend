package project.study.metrics.report.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

/** 🧑‍🎓 어제 공부한 유저 스레드 (부록 A 스레드 6). 단계 기준 문장을 스레드 첫머리에 그대로 쓴다. */
public final class ActiveUsersRenderer {

    static final int ROW_LIMIT = 100;

    private static final Map<Stage, String> ICONS =
            Map.of(Stage.NEW, "🌱", Stage.STEADY, "🔥", Stage.RETURNING, "🔄", Stage.OCCASIONAL, "🌙");
    private static final List<String> CRITERIA = List.of(
            "단계 기준 (새로 온 사람이 먼저)",
            "• 새로 온 사람: 어제가 가입 첫 주(가입일 포함 7일) 안",
            "• 꾸준한 사람: 최근 7일(어제 포함) 중 3일 이상 공부",
            "• 돌아온 사람: 첫 주가 지났고, 어제 전 14일 동안 공부 안 하다가 어제 공부 (가입 뒤 처음 공부한 사람 포함)",
            "• 가끔 오는 사람: 나머지 — 최근 7일 중 1~2일 공부");

    private ActiveUsersRenderer() {}

    public static String render(ActiveUsersSection section) {
        List<String> lines = new ArrayList<>();
        if (section.total() == 0) {
            lines.add("🧑‍🎓 어제 공부한 사람 없음");
        } else {
            lines.add("🧑‍🎓 어제 공부한 %d명".formatted(section.total()));
            lines.addAll(CRITERIA);
            lines.add("");
            lines.addAll(stages(section));
        }
        lines.add("");
        lines.add("앱만 열고 공부 안 함: " + (section.appOnly() == null ? "Amplitude 미수집" : section.appOnly() + "명"));
        return String.join("\n", lines);
    }

    // 메시지 전체 100줄 상한 — 넘는 사람은 "외 N명"으로 (스펙 §4.6)
    private static List<String> stages(ActiveUsersSection section) {
        List<String> lines = new ArrayList<>();
        int printed = 0;
        for (Stage stage : Stage.values()) {
            List<ActiveUserRow> rows = section.byStage().getOrDefault(stage, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            lines.add(ICONS.get(stage) + " " + stage.label() + " " + rows.size());
            for (ActiveUserRow row : rows.subList(0, Math.min(rows.size(), ROW_LIMIT - printed))) {
                lines.add("• " + row(row));
                printed++;
            }
        }
        if (printed < section.total()) {
            lines.add("⋯ 외 %d명".formatted(section.total() - printed));
        }
        return lines;
    }

    private static String row(ActiveUserRow row) {
        String joined = row.daysSinceSignup() == 0 ? "어제 가입" : "가입 " + row.daysSinceSignup() + "일 뒤";
        String app = row.appDays7() == null ? "" : " · 최근 7일 앱 실행 " + row.appDays7() + "일";
        return "%s(#%d) · %s · 연속 %s · 어제 순공 %s · %s 위주 · 과목 %d개 · %s%s"
                .formatted(
                        row.nickname(),
                        row.userId(),
                        joined,
                        Fmt.streak(row.streak()),
                        Fmt.duration(row.focusSec()),
                        row.socialMostly() ? "소셜" : "싱글",
                        row.liveSubjects(),
                        row.profile(),
                        app);
    }
}
