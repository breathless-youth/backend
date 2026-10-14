package project.study.metrics.report.analyzer;

import java.util.List;
import java.util.Map;

/**
 * 🧑‍🎓 어제 공부한 유저 (스펙 §4.6).
 *
 * @param appOnly 어제 앱을 열었지만 공부한 날이 아닌 사람 수, 어제 Amplitude가 미수집이면 null
 */
public record ActiveUsersSection(int total, Map<Stage, List<ActiveUserRow>> byStage, Integer appOnly) {

    /** 선언 순서가 요약·스레드의 표시 순서다. 판정 순서는 ActiveUserClassifier.stage. 이름은 팀이 부르는 영어 이름(BY-910). */
    public enum Stage {
        NEW("New"),
        LOVERS("Lovers"),
        COMEBACK("Comeback"),
        SOMETIMES("Sometimes");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param streak A부터 거꾸로 센 연속 공부한 날
     * @param socialMostly 어제 순공의 절반 이상이 소셜 세션
     * @param appDays7 최근 7일 앱 실행 일수, Amplitude 미수집이면 null
     */
    public record ActiveUserRow(
            long userId,
            String nickname,
            long daysSinceSignup,
            int streak,
            int focusSec,
            boolean socialMostly,
            int liveSubjects,
            String profile,
            Integer appDays7) {}

    public int count(Stage stage) {
        return byStage.getOrDefault(stage, List.of()).size();
    }
}
