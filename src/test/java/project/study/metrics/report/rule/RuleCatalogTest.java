package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;

class RuleCatalogTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);
    private static final CohortStats NO_STATS = new CohortStats(Double.NaN, Double.NaN);

    private static SegmentRule rule(String key) {
        return RuleCatalog.all().stream()
                .filter(rule -> rule.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static boolean matches(String key, UserTimeline user) {
        return rule(key).matches(user, NO_STATS);
    }

    @Test
    void 카탈로그는_묶음별로_40개다() {
        Map<RuleGroup, Long> byGroup =
                RuleCatalog.all().stream().collect(Collectors.groupingBy(SegmentRule::group, Collectors.counting()));

        assertThat(RuleCatalog.all()).hasSize(40);
        assertThat(RuleCatalog.all().stream().map(SegmentRule::key).distinct()).hasSize(40);
        assertThat(byGroup)
                .containsEntry(RuleGroup.STREAK, 12L)
                .containsEntry(RuleGroup.STUDY_DAYS, 4L)
                .containsEntry(RuleGroup.FIRST_DAY, 2L)
                .containsEntry(RuleGroup.FEATURE, 5L)
                .containsEntry(RuleGroup.APP_EVENT, 6L)
                .containsEntry(RuleGroup.HABIT, 5L)
                .containsEntry(RuleGroup.SCREEN, 3L)
                .containsEntry(RuleGroup.INVITE, 1L)
                .containsEntry(RuleGroup.RECORD_PUSH, 2L);
        assertThat(RuleCatalog.behaviors()).hasSize(22);
    }

    @Test
    void 규칙_이름은_파라미터에서_만든다() {
        Map<String, String> names =
                RuleCatalog.all().stream().collect(Collectors.toMap(SegmentRule::key, SegmentRule::name));

        assertThat(names)
                .containsEntry("streak-2-5", "첫 주에 2일 연속 공부")
                .containsEntry("streak-3-60", "첫 주에 3일 연속 하루 순공 1시간 이상")
                .containsEntry("streak-4-120", "첫 주에 4일 연속 하루 순공 2시간 이상")
                .containsEntry("study-days-3", "첫 주에 공부한 날 3일 이상")
                .containsEntry("first-day-30", "가입한 날 순공 30분 이상")
                .containsEntry("room-3", "첫 주에 소셜 방 3번 이상 참여")
                .containsEntry("invite-opened", "가입 이틀 안에 초대 링크로 들어옴");
        assertThat(RuleCatalog.fixed(rule(RuleCatalog.FIXED_RULE_KEY))).isTrue();
        assertThat(rule("room-3").phrase()).isEqualTo("소셜 방 3번 이상 참여");
    }

    @Test
    void 연속_규칙은_하루_순공_하한을_넘긴_날이_이어져야_한다() {
        UserTimeline twoHourDays =
                user(1, D0).studyOnDay(1, 60).studyOnDay(2, 60).build();
        UserTimeline broken = user(2, D0).studyOnDay(1, 60).studyOnDay(2, 20).build();

        assertThat(matches("streak-2-60", twoHourDays)).isTrue();
        assertThat(matches("streak-2-60", broken)).isFalse();
        assertThat(matches("streak-2-5", broken)).isTrue();
    }

    @Test
    void 첫_주_규칙은_D0부터_D6까지만_본다() {
        UserTimeline user = user(1, D0)
                .studyOnDays(0, 3, 7)
                .activity(Activity.ROOM_JOIN, 6, 3)
                .activity(Activity.ROOM_JOIN, 7, 5)
                .build();

        assertThat(matches("study-days-2", user)).isTrue();
        assertThat(matches("study-days-3", user)).isFalse();
        assertThat(matches("room-3", user)).isTrue();
        assertThat(matches("first-day-30", user)).isTrue();
        assertThat(matches("first-day-60", user)).isFalse();
    }

    @Test
    void Amplitude_규칙은_이벤트_키로_판정한다() {
        UserTimeline user = user(1, D0)
                .amp(AmplitudeKeys.APP_LAUNCHED, 0, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 2, 4)
                .amp(AmplitudeKeys.APP_LAUNCHED, 5, 1)
                .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1)
                .amp(AmplitudeKeys.INVITE_OPENED, 2, 1) // 이틀(D0~D1) 밖
                .build();

        assertThat(matches("app-days-3", user)).isTrue();
        assertThat(matches("simple-mode", user)).isTrue();
        assertThat(matches("landscape", user)).isFalse();
        assertThat(matches("invite-opened", user)).isFalse();
        assertThat(rule("simple-mode").amplitude()).isTrue();
        assertThat(rule("room-3").amplitude()).isFalse();
    }

    @Test
    void 중앙값_규칙은_코호트_통계와_비교하고_값이_없으면_안_한_것이다() {
        UserTimeline calm = user(1, D0).session(D0, 20, 30, 1, false).build();
        UserTimeline restless = user(2, D0).session(D0, 20, 30, 4, false).build();
        CohortStats stats = new CohortStats(2.0, Double.NaN);
        Function<UserTimeline, Boolean> fewPauses = user -> rule("few-pauses").matches(user, stats);

        assertThat(fewPauses.apply(calm)).isTrue();
        assertThat(fewPauses.apply(restless)).isFalse();
        assertThat(fewPauses.apply(user(3, D0).build())).isFalse();
        assertThat(rule("few-distractions").matches(calm, stats)).isFalse(); // 중앙값 NaN
    }

    @Test
    void 시간대_규칙은_순공_절반_이상이_그_시간대에_시작했는지_본다() {
        UserTimeline night = user(1, D0)
                .session(D0, 23, 60, 0, false)
                .session(D0, 15, 50, 0, false)
                .build();
        UserTimeline morning = user(2, D0)
                .session(D0, 6, 40, 0, false)
                .session(D0, 13, 40, 0, false)
                .build();

        assertThat(matches("night-study", night)).isTrue();
        assertThat(matches("morning-study", morning)).isTrue();
        assertThat(matches("morning-study", night)).isFalse();
        assertThat(matches("long-sessions", night)).isTrue();
    }
}
