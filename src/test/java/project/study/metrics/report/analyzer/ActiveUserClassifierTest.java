package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

class ActiveUserClassifierTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    private static UserTimeline[] users() {
        return new UserTimeline[] {
            user(1, A.minusDays(2))
                    .profile("공시생3년차", "CIVIL_SERVICE", "9급")
                    .session(A, 9, 60, 0, true)
                    .subject(0, null)
                    .amp(AmplitudeKeys.APP_LAUNCHED, 1, 1)
                    .amp(AmplitudeKeys.APP_LAUNCHED, 2, 1)
                    .build(),
            user(2, A)
                    .profile("아침형인간", "CSAT", null)
                    .session(A, 20, 160, 0, false)
                    .build(),
            user(3, A.minusDays(20)).studyOnDays(18, 19, 20).build(), // A−2, A−1, A
            user(4, A.minusDays(31)).studyOnDays(11, 31).build(), // A−20, A — 쉬다 옴
            user(5, A.minusDays(10)).studyOnDays(10).build(), // 가입 뒤 처음 공부 — Comeback
            user(6, A.minusDays(30)).studyOnDays(22, 30).build(), // A−8, A — 어제 전 7일을 쉬었다
            user(7, A.minusDays(30)).studyOnDays(23, 30).build(), // A−7, A — 7일 안에 공부해 가끔
            user(9, A.minusDays(30)).studyOnDays(29).build(), // 어제는 안 함
            user(8, A.minusDays(40)).ampOn(AmplitudeKeys.APP_LAUNCHED, A, 2).build() // 앱만 열었다
        };
    }

    @Test
    void 단계는_New가_먼저이고_나머지는_최근_공부_패턴으로_나눈다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        assertThat(section.total()).isEqualTo(7);
        assertThat(section.count(Stage.NEW)).isEqualTo(2);
        assertThat(section.count(Stage.LOVERS)).isEqualTo(1);
        assertThat(section.count(Stage.COMEBACK)).isEqualTo(3);
        assertThat(section.count(Stage.SOMETIMES)).isEqualTo(1);
        assertThat(section.byStage().get(Stage.NEW))
                .extracting(ActiveUserRow::userId)
                .containsExactly(2L, 1L);
        assertThat(section.appOnly()).isEqualTo(1);
    }

    @Test
    void Comeback은_어제_전_7일_동안_공부하지_않은_사람이다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        // A−8에 공부하고 어제 온 사람은 Comeback, A−7에 공부한 사람은 7일 안이라 Sometimes
        assertThat(section.byStage().get(Stage.COMEBACK))
                .extracting(ActiveUserRow::userId)
                .contains(6L)
                .doesNotContain(7L);
        assertThat(section.byStage().get(Stage.SOMETIMES))
                .extracting(ActiveUserRow::userId)
                .containsExactly(7L);
    }

    @Test
    void 한_줄_프로필에_필요한_값을_담는다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        ActiveUserRow row = section.byStage().get(Stage.NEW).get(1);
        assertThat(row).isEqualTo(new ActiveUserRow(1, "공시생3년차", 2, 1, 3600, true, 1, "공무원(9급)", 2));
        assertThat(section.byStage().get(Stage.NEW).getFirst().daysSinceSignup())
                .isZero();
    }

    @Test
    void Amplitude가_없으면_앱_관련_값을_비운다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, false, users()));

        assertThat(section.appOnly()).isNull();
        assertThat(section.byStage().get(Stage.NEW))
                .extracting(ActiveUserRow::appDays7)
                .containsOnlyNulls();
    }
}
