package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

class ActiveUsersRendererTest {

    @Test
    void 단계_기준과_단계별_한_줄_프로필을_쓴다() {
        assertThat(ActiveUsersRenderer.render(ReportSamples.active()).lines())
                .containsSubsequence(
                        "🧑‍🎓 어제 공부한 15명",
                        "단계 기준 (New가 먼저)",
                        "• New: 어제가 가입 첫 주(가입일 포함 7일) 안",
                        "• Lovers: 최근 7일(어제 포함) 중 3일 이상 공부",
                        "• Comeback: 첫 주가 지났고, 어제 전 7일 동안 공부 안 하다가 어제 공부 (가입 뒤 처음 공부한 사람 포함)",
                        "• Sometimes: 나머지 — 최근 7일 중 1~2일 공부",
                        "🌱 New 6",
                        "• 아침형인간(#531) · 어제 가입 · 연속 1일 · 어제 순공 2시간 40분 · 싱글 위주 · 과목 2개 · 수능 · 최근 7일 앱 실행 1일",
                        "🔥 Lovers 5",
                        "• 공부하는곰(#418) · 가입 13일 뒤 · 연속 3일 · 어제 순공 3시간 30분 · 소셜 위주 · 과목 4개 · 공무원(7급 합격) · 최근 7일 앱 실행 6일",
                        "🔄 Comeback 2",
                        "🌙 Sometimes 2",
                        "앱만 열고 공부 안 함: 9명");
    }

    @Test
    void 백_줄을_넘으면_나머지는_외_N명으로_줄이고_Amplitude가_없으면_그렇게_쓴다() {
        List<ActiveUserRow> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(new ActiveUserRow(i, "유저" + i, 1, 1, 600, false, 0, "분야 미입력", null));
        }
        String text = ActiveUsersRenderer.render(new ActiveUsersSection(101, Map.of(Stage.NEW, rows), null));

        assertThat(text.lines().filter(line -> line.startsWith("• 유저"))).hasSize(100);
        assertThat(text.lines()).contains("⋯ 외 1명", "앱만 열고 공부 안 함: Amplitude 미수집");
    }

    @Test
    void 어제_공부한_사람이_없으면_한_줄로_쓴다() {
        assertThat(ActiveUsersRenderer.render(new ActiveUsersSection(0, Map.of(), 3)))
                .isEqualTo("🧑‍🎓 어제 공부한 사람 없음\n\n앱만 열고 공부 안 함: 3명");
    }
}
