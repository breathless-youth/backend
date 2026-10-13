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
            user(4, A.minusDays(31)).studyOnDays(11, 31).build(), // A−20, A — 2주 쉬다 옴
            user(5, A.minusDays(10)).studyOnDays(10).build(), // 가입 뒤 처음 공부 — 돌아온 사람
            user(6, A.minusDays(30)).studyOnDays(20, 30).build(), // A−10, A — 가끔
            user(7, A.minusDays(30)).studyOnDays(29).build(), // 어제는 안 함
            user(8, A.minusDays(40)).ampOn(AmplitudeKeys.APP_LAUNCHED, A, 2).build() // 앱만 열었다
        };
    }

    @Test
    void 단계는_새로_온_사람이_먼저이고_나머지는_최근_공부_패턴으로_나눈다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        assertThat(section.total()).isEqualTo(6);
        assertThat(section.count(Stage.NEW)).isEqualTo(2);
        assertThat(section.count(Stage.STEADY)).isEqualTo(1);
        assertThat(section.count(Stage.RETURNING)).isEqualTo(2);
        assertThat(section.count(Stage.OCCASIONAL)).isEqualTo(1);
        assertThat(section.byStage().get(Stage.NEW))
                .extracting(ActiveUserRow::userId)
                .containsExactly(2L, 1L);
        assertThat(section.appOnly()).isEqualTo(1);
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
