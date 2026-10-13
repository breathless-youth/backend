package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FirstWeekHabitsTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 29);

    @Test
    void 습관_값은_첫_주의_순공_1분_이상_세션만_센다() {
        UserTimeline timeline = user(1, D0)
                .session(D0, 22, 60, 1, true)
                .session(D0.plusDays(1), 22, 30, 0, false)
                .sessionAt(TimelineBuilder.at(D0, 9), D0, 30) // 30초 — 습관에서 빠진다
                .session(D0.plusDays(7), 7, 90, 5, false) // 둘째 주 — 빠진다
                .build();

        assertThat(FirstWeekHabits.sessions(timeline)).hasSize(2);
        assertThat(FirstWeekHabits.pausesPerSession(timeline).getAsDouble()).isEqualTo(0.5);
        assertThat(FirstWeekHabits.averageSessionFocusSec(timeline).getAsDouble())
                .isEqualTo(2700);
        assertThat(FirstWeekHabits.socialFocusPercent(timeline).getAsInt()).isEqualTo(67);
        assertThat(FirstWeekHabits.topStartBand(timeline).getAsInt()).isEqualTo(7); // 21~24시
        assertThat(FirstWeekHabits.focusShareStartedIn(timeline, FirstWeekHabits.NIGHT_HOURS))
                .isEqualTo(1.0);
    }

    @Test
    void 딴짓은_첫_주_순공_1시간당_횟수다() {
        UserTimeline timeline = user(1, D0)
                .studyOnDay(0, 120)
                .amp(AmplitudeKeys.DISTRACTED, 0, 3)
                .build();

        assertThat(FirstWeekHabits.distractionsPerFocusHour(timeline).getAsDouble())
                .isCloseTo(1.5, within(1e-9));
    }

    @Test
    void 세션이_없으면_값이_없다() {
        UserTimeline timeline = user(1, D0).build();

        assertThat(FirstWeekHabits.pausesPerSession(timeline)).isEmpty();
        assertThat(FirstWeekHabits.distractionsPerFocusHour(timeline)).isEmpty();
        assertThat(FirstWeekHabits.topStartBand(timeline)).isEmpty();
        assertThat(FirstWeekHabits.socialFocusPercent(timeline)).isEmpty();
        assertThat(FirstWeekHabits.focusShareStartedIn(timeline, FirstWeekHabits.MORNING_HOURS))
                .isZero();
    }
}
