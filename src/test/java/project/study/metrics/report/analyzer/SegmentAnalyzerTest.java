package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.TimelineBuilder;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;

class SegmentAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate SIGNUP = A.minusDays(20);

    /** 첫 주 3일+ 6명(4명 남음) · 1~2일 5명(1명 남음) · 0일 10명. 3일+는 모두 방 3번, 3명은 D-day. */
    private static List<UserTimeline> cohort() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TimelineBuilder builder = user(100 + i, SIGNUP)
                    .profile("s" + i, i < 4 ? "CSAT" : i == 4 ? "CIVIL_SERVICE" : null, null)
                    .studyOnDays(0, 2, 4)
                    .activity(Activity.ROOM_JOIN, 1, 3)
                    .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1);
            if (i < 3) {
                builder.activity(Activity.DDAY_SET, 0, 1);
            }
            if (i < 4) {
                builder.studyOnDays(8);
            }
            users.add(builder.build());
        }
        for (int i = 0; i < 5; i++) {
            TimelineBuilder builder = user(200 + i, SIGNUP).studyOnDay(1, 12);
            users.add(i == 0 ? builder.studyOnDay(9, 12).build() : builder.build());
        }
        for (int i = 0; i < 10; i++) {
            users.add(user(300 + i, SIGNUP).build());
        }
        users.add(user(400, A.minusDays(12)).studyOnDays(0, 1, 2).build()); // 둘째 주가 안 끝났다 — 코호트 밖
        return users;
    }

    @Test
    void 코호트를_첫_주_공부한_날로_세_묶음으로_나눠_둘째_주를_비교한다() {
        SegmentSection section =
                SegmentAnalyzer.analyze(ReportDataFixture.data(A, false, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.cohortFrom()).isEqualTo(A.minusDays(40));
        assertThat(section.cohortTo()).isEqualTo(A.minusDays(13));
        assertThat(section.cohortSize()).isEqualTo(21);
        assertThat(section.groups())
                .containsExactly(
                        new GroupRow("3일 이상", 6, Rate.of(4, 6)),
                        new GroupRow("1~2일", 5, Rate.of(1, 5)),
                        new GroupRow("0일", 10, Rate.of(0, 10)));
        assertThat(section.table().doerRetention()).isEqualTo(Rate.of(4, 6));
        assertThat(section.table().nonDoerRetention()).isEqualTo(Rate.of(1, 15));
    }

    @Test
    void 주로_한_행동은_3일_이상의_30퍼센트_이상이_한_것_중_차이가_큰_순_5개다() {
        SegmentSection section =
                SegmentAnalyzer.analyze(ReportDataFixture.data(A, false, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.enoughForBehaviors()).isTrue();
        assertThat(section.amplitudeCollected()).isFalse();
        assertThat(section.behaviors())
                .extracting(BehaviorRow::label)
                .containsExactly(
                        "소셜 방 1번 이상 참여", "소셜 방 3번 이상 참여", "세션 평균 순공 30분 이상", "세션당 일시정지가 적음(중앙값 이하)", "D-day 설정");
        assertThat(section.profile()).isEqualTo(new BehaviorRow("프로필(분야·목표) 입력", Rate.of(5, 6), Rate.of(0, 15)));
        assertThat(section.who().getFirst().title()).isEqualTo("분야");
        assertThat(section.who().getFirst().rows()).extracting(ShareRow::label).containsExactly("수능", "공무원", "미입력");
        // 유입 정보(Amplitude 속성)가 아무에게도 없으면 광고 유입은 0%가 아니라 모른다
        ShareRow ads = section.who().getLast().rows().getFirst();
        assertThat(ads.segment().defined()).isFalse();
        assertThat(ads.rest().defined()).isFalse();
    }

    @Test
    void Amplitude가_수집됐으면_앱_행동도_후보다() {
        SegmentSection section =
                SegmentAnalyzer.analyze(ReportDataFixture.data(A, true, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.amplitudeCollected()).isTrue();
        assertThat(section.behaviors()).extracting(BehaviorRow::label).contains("간단 모드 켬");
    }

    @Test
    void 첫_주_3일_이상이_5명_미만이면_행동과_특징_비교를_하지_않는다() {
        SegmentSection section = SegmentAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, SIGNUP).studyOnDays(0, 1, 2).build(),
                user(2, SIGNUP).build()));

        assertThat(section.enoughForBehaviors()).isFalse();
        assertThat(section.behaviors()).isEmpty();
        assertThat(section.who()).isEmpty();
    }
}
