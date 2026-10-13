package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.at;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;

class SignupAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Test
    void 어제_가입_수_회원번호_범위와_3시간_안_공부를_센다() {
        UserTimeline quick =
                user(503, A).signupHour(21).session(A, 22, 30, 0, false).build();
        // 23시 가입 → 다음 날 00:30 시작 세션도 3시간 안이다
        UserTimeline lateNight = user(510, A)
                .signupHour(23)
                .sessionAt(at(A.plusDays(1), 0).plusSeconds(1800), A.plusDays(1), 900)
                .build();
        UserTimeline idle = user(520, A).signupHour(10).build();
        UserTimeline older = user(400, A.minusDays(7)).build();

        SignupSection section = SignupAnalyzer.analyze(ReportDataFixture.data(A, true, quick, lateNight, idle, older));

        assertThat(section.yesterdayCount()).isEqualTo(3);
        assertThat(section.minUserId()).isEqualTo(503L);
        assertThat(section.maxUserId()).isEqualTo(520L);
        assertThat(section.yesterdayQuick()).isEqualTo(Rate.of(2, 3));
        assertThat(section.weekTotal()).isEqualTo(3);
        assertThat(section.weekFrom()).isEqualTo(A.minusDays(6));
    }

    @Test
    void 시간대별_가입과_바로_공부_비율이_가장_높은_구간을_고른다() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // 06~09시 5명 중 2명 바로 공부
            var builder = user(100 + i, A.minusDays(2)).signupHour(7);
            users.add(i < 2 ? builder.session(A.minusDays(2), 8, 30, 0, false).build() : builder.build());
        }
        for (int i = 0; i < 8; i++) { // 21~24시 8명 중 1명 — 가입은 가장 많다
            var builder = user(200 + i, A.minusDays(1)).signupHour(22);
            users.add(i < 1 ? builder.session(A.minusDays(1), 23, 30, 0, false).build() : builder.build());
        }
        // 00~03시 1명이 바로 공부(100%)해도 5명 미만이라 비율 후보가 아니다
        users.add(user(300, A.minusDays(3))
                .signupHour(1)
                .session(A.minusDays(3), 2, 30, 0, false)
                .build());

        SignupSection section =
                SignupAnalyzer.analyze(ReportDataFixture.data(A, true, users.toArray(UserTimeline[]::new)));

        assertThat(section.bands()).hasSize(8);
        assertThat(section.bands().get(2)).isEqualTo(new HourBand(6, 5, 2));
        assertThat(section.bands().get(7)).isEqualTo(new HourBand(21, 8, 1));
        assertThat(section.peak()).contains(new HourBand(21, 8, 1));
        assertThat(section.bestQuick()).contains(new HourBand(6, 5, 2));
    }

    @Test
    void 공부_분야와_유입을_미입력_미수집과_함께_센다() {
        UserTimeline csat1 = user(1, A)
                .profile("a", "CSAT", null)
                .attribute("instagram", true, "ios")
                .build();
        UserTimeline csat2 = user(2, A)
                .profile("b", "CSAT", null)
                .attribute("instagram", false, "android")
                .build();
        UserTimeline civil = user(3, A)
                .profile("c", "CIVIL_SERVICE", null)
                .attribute("tiktok", true, null)
                .build();
        UserTimeline none = user(4, A).profile("d", null, "합격").build();

        SignupSection section = SignupAnalyzer.analyze(ReportDataFixture.data(A, true, csat1, csat2, civil, none));

        assertThat(section.categories()).containsExactly(new Share("수능", 2), new Share("공무원", 1), new Share("미입력", 1));
        assertThat(section.attribution())
                .isEqualTo(new Attribution(4, 2, List.of(new Share("instagram", 2), new Share("tiktok", 1)), 1));
    }

    @Test
    void 최근_7일_가입이_없으면_구간도_고르지_않는다() {
        SignupSection section = SignupAnalyzer.analyze(ReportDataFixture.data(A, false));

        assertThat(section.yesterdayCount()).isZero();
        assertThat(section.minUserId()).isNull();
        assertThat(section.yesterdayQuick().defined()).isFalse();
        assertThat(section.peak()).isEmpty();
        assertThat(section.bestQuick()).isEmpty();
        assertThat(section.categories()).isEmpty();
    }
}
