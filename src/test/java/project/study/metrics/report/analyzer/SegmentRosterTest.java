package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;

class SegmentRosterTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate FRESH = A.minusDays(13);

    private static UserTimeline bear() {
        return user(418, FRESH)
                .signupHour(9)
                .profile("공부하는곰", "CIVIL_SERVICE", "7급 합격")
                .attribute("instagram", true, "ios")
                .studyOnDay(0, 60)
                .studyOnDay(1, 60)
                .studyOnDay(2, 60)
                .studyOnDay(3, 60)
                .studyOnDay(5, 60)
                .studyOnDays(10, 11, 12, 13)
                .activity(Activity.ROOM_JOIN, 1, 7)
                .subject(0, null)
                .subject(0, null)
                .subject(0, null)
                .subject(0, 3)
                .activity(Activity.TASK_DONE, 2, 12)
                .activity(Activity.DDAY_SET, 0, 1)
                .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1)
                .amp(AmplitudeKeys.LANDSCAPE, 2, 1)
                .amp(AmplitudeKeys.AMBIENT_SOUND, 3, 2)
                .amp(AmplitudeKeys.RANKING, 4, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 0, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 1, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 2, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 3, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 4, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 5, 2)
                .build();
    }

    @Test
    void 첫_주_3일_이상을_남은_사람과_떠난_사람으로_나눠_최신_가입부터_보여준다() {
        UserTimeline older = user(409, A.minusDays(20)).studyOnDays(0, 1, 2, 8).build();
        UserTimeline left = user(421, FRESH).studyOnDays(0, 1, 2).build();
        UserTimeline light = user(500, FRESH).studyOnDays(0, 8).build(); // 첫 주 1일 — 명단 밖

        RosterSection section = SegmentRoster.analyze(ReportDataFixture.data(A, true, older, bear(), left, light));

        assertThat(section.total()).isEqualTo(3);
        assertThat(section.retained().cards()).extracting(RosterCard::userId).containsExactly(418L, 409L);
        assertThat(section.retained().cards()).extracting(RosterCard::fresh).containsExactly(true, false);
        assertThat(section.dropped().cards()).singleElement().satisfies(card -> {
            assertThat(card.fresh()).isTrue();
            assertThat(card.attribution()).isEqualTo("유입 미수집");
            assertThat(card.flow().secondWeekDays()).isZero();
            assertThat(card.flow().lastStudyDay()).isEqualTo(FRESH.plusDays(2));
            assertThat(card.flow().lastInFirstWeek()).isTrue();
            assertThat(card.flow().currentStreak()).isZero();
        });
    }

    @Test
    void 카드는_누구_공부_흐름_첫_주에_한_일_습관을_담는다() {
        RosterCard card = SegmentRoster.analyze(ReportDataFixture.data(A, true, bear()))
                .retained()
                .cards()
                .getFirst();

        assertThat(card.nickname()).isEqualTo("공부하는곰");
        assertThat(card.profile()).isEqualTo("공무원(7급 합격)");
        assertThat(card.os()).isEqualTo("iOS");
        assertThat(card.attribution()).isEqualTo("유입: 광고(instagram)");
        assertThat(card.flow()).isEqualTo(new StudyFlow(5, 5 * 3600, 4, 4, 4 * 1800, A, false, 4));
        assertThat(card.actions())
                .containsExactly("소셜 방 7회", "과목 4개", "할 일 완료 12개", "D-day", "간단 모드", "가로 화면", "백색소음", "랭킹", "앱 실행 6일");
        assertThat(card.amplitudePartial()).isFalse();
        assertThat(card.habits().averageSessionFocusSec().getAsDouble()).isEqualTo(3600);
        assertThat(card.habits().topBandStartHour().getAsInt()).isEqualTo(18);
        assertThat(card.habits().socialPercent().getAsInt()).isZero();
        assertThat(card.habits().distractionsPerHour().getAsDouble()).isZero();
    }

    @Test
    void Amplitude가_빠진_기간이면_표시하고_딴짓은_비운다() {
        RosterCard card = SegmentRoster.analyze(ReportDataFixture.data(A, false, bear()))
                .retained()
                .cards()
                .getFirst();

        assertThat(card.amplitudePartial()).isTrue();
        assertThat(card.habits().distractionsPerHour()).isEmpty();
    }

    @Test
    void 묶음마다_30명까지만_카드로_만들고_나머지는_가입일로_알린다() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            users.add(user(1000 + i, A.minusDays(13 + i % 28))
                    .studyOnDays(0, 1, 2, 8)
                    .build());
        }

        RosterSection section =
                SegmentRoster.analyze(ReportDataFixture.data(A, false, users.toArray(UserTimeline[]::new)));

        assertThat(section.retained().total()).isEqualTo(31);
        assertThat(section.retained().cards()).hasSize(30);
        assertThat(section.retained().omitted()).isEqualTo(1);
        assertThat(section.retained().omittedSignupOnOrBefore()).isEqualTo(A.minusDays(40));
    }
}
