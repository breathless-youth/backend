package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.at;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.UserTimeline.Activity;

class UserTimelineTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 29);

    @Test
    void 가입일은_KST_날짜다() {
        // 2026-09-28T15:30Z = 9/29 00:30 KST
        UserTimeline timeline = user(1, D0)
                .signupAt(java.time.Instant.parse("2026-09-28T15:30:00Z"))
                .build();

        assertThat(timeline.signupDate()).isEqualTo(D0);
        assertThat(timeline.day(7)).isEqualTo(D0.plusDays(7));
    }

    @Test
    void 공부한_날은_5분_이상_세션이_있는_날이고_하루_순공은_모든_세션의_합이다() {
        UserTimeline timeline =
                user(1, D0).studyOnDay(0, 4).studyOnDay(0, 3).studyOnDay(1, 5).build();

        assertThat(timeline.studied(D0)).isFalse();
        assertThat(timeline.focusSec(D0)).isEqualTo(7 * 60);
        assertThat(timeline.studied(D0.plusDays(1))).isTrue();
        assertThat(timeline.studyDays(D0, D0.plusDays(6))).isEqualTo(1);
    }

    @Test
    void 자정_분할로_조각난_세션은_각_조각이_5분_미만이면_공부한_날이_아니다() {
        // ADR-0012 결정 3 — 23:56~00:04 8분 세션이 4분·4분 두 조각이면 어느 날도 공부한 날이 아니다
        UserTimeline timeline = user(1, D0)
                .sessionAt(at(D0, 23).plusSeconds(56 * 60), D0, 240)
                .sessionAt(at(D0.plusDays(1), 0), D0.plusDays(1), 240)
                .build();

        assertThat(timeline.studyDays(D0, D0.plusDays(1))).isZero();
    }

    @Test
    void 가장_긴_연속은_하루_순공_하한을_넘긴_공부한_날만_잇는다() {
        UserTimeline timeline = user(1, D0)
                .studyOnDay(0, 60)
                .studyOnDay(1, 20)
                .studyOnDay(2, 60)
                .studyOnDay(3, 60)
                .studyOnDay(5, 60)
                .build();

        assertThat(timeline.longestRun(D0, D0.plusDays(6), 600)).isEqualTo(4);
        assertThat(timeline.longestRun(D0, D0.plusDays(6), 3600)).isEqualTo(2);
        assertThat(timeline.longestRun(D0.plusDays(4), D0.plusDays(4), 600)).isZero();
    }

    @Test
    void 연속과_마지막_공부일은_기준일부터_거꾸로_센다() {
        UserTimeline timeline = user(1, D0).studyOnDays(0, 2, 3, 4).build();

        assertThat(timeline.streakEndingAt(D0.plusDays(4))).isEqualTo(3);
        assertThat(timeline.streakEndingAt(D0.plusDays(5))).isZero();
        assertThat(timeline.lastStudyDay(D0.plusDays(10))).contains(D0.plusDays(4));
        assertThat(timeline.lastStudyDay(D0.plusDays(1))).contains(D0);
        assertThat(user(2, D0).build().lastStudyDay(D0)).isEmpty();
    }

    @Test
    void 활동과_Amplitude_횟수는_KST_날짜_범위로_센다() {
        UserTimeline timeline = user(1, D0)
                .activity(Activity.ROOM_JOIN, 0, 2)
                .activity(Activity.ROOM_JOIN, 7, 1)
                .amp("app_launched", 0, 3)
                .amp("app_launched", 2, 1)
                .amp("app_launched", 9, 5)
                .build();

        assertThat(timeline.activityCount(Activity.ROOM_JOIN, D0, D0.plusDays(6)))
                .isEqualTo(2);
        assertThat(timeline.amplitudeCount("app_launched", D0, D0.plusDays(6))).isEqualTo(4);
        assertThat(timeline.amplitudeDays("app_launched", D0, D0.plusDays(6))).isEqualTo(2);
        assertThat(timeline.amplitudeCount("planner_opened", D0, D0.plusDays(6)))
                .isZero();
    }

    @Test
    void 과목은_만든_날을_활동으로_세고_그_시각에_살아있는_과목_수도_센다() {
        UserTimeline timeline =
                user(1, D0).subject(0, null).subject(1, 3).subject(8, null).build();

        assertThat(timeline.activityCount(Activity.SUBJECT_CREATED, D0, D0.plusDays(6)))
                .isEqualTo(2);
        assertThat(timeline.liveSubjectsAt(at(D0.plusDays(2), 0))).isEqualTo(2);
        assertThat(timeline.liveSubjectsAt(at(D0.plusDays(9), 0))).isEqualTo(2);
    }

    @Test
    void 가입_3시간_안에_시작한_세션의_순공을_자정을_넘어서도_더한다() {
        // 23시 가입 → 00:30 시작 세션은 다음 날 stat_date지만 3시간 안이다
        UserTimeline timeline = user(1, D0)
                .signupHour(23)
                .sessionAt(at(D0.plusDays(1), 0).plusSeconds(1800), D0.plusDays(1), 700)
                .sessionAt(at(D0.plusDays(1), 2).plusSeconds(1), D0.plusDays(1), 900)
                .build();

        assertThat(timeline.focusStartedWithin(Duration.ofHours(3))).isEqualTo(700);
    }
}
