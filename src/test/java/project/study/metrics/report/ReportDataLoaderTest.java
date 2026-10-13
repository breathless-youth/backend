package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.amplitude.AmplitudeQueryService;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class ReportDataLoaderTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final Instant FACT_FROM = Kst.startOf(A.minusDays(61));
    private static final Instant FACT_TO = Kst.startOf(A.plusDays(1));

    @Mock
    private UserService userService;

    @Mock
    private StudySessionMetricsService sessionMetricsService;

    @Mock
    private RoomMetricsService roomMetricsService;

    @Mock
    private SubjectMetricsService subjectMetricsService;

    @Mock
    private DdayMetricsService ddayMetricsService;

    @Mock
    private AmplitudeQueryService amplitudeQueryService;

    private ReportDataLoader loader(Set<Long> excluded) {
        return new ReportDataLoader(
                userService,
                sessionMetricsService,
                roomMetricsService,
                subjectMetricsService,
                ddayMetricsService,
                amplitudeQueryService,
                excluded);
    }

    private void stubEmptyExtras(Set<Long> userIds) {
        when(subjectMetricsService.findTasksDone(FACT_FROM, FACT_TO)).thenReturn(List.of());
        when(ddayMetricsService.findDdaysOf(userIds)).thenReturn(List.of());
        when(subjectMetricsService.findSubjectsOf(userIds)).thenReturn(List.of());
        when(amplitudeQueryService.collectedDates(A.minusDays(61), A)).thenReturn(Set.of(A));
    }

    @Test
    void 정해진_기간을_읽어_유저별_타임라인으로_묶는다() {
        Instant signup = Kst.startOf(A.minusDays(3)).plusSeconds(3600);
        when(sessionMetricsService.findSessionFacts(A.minusDays(61), A.plusDays(1)))
                .thenReturn(List.of(new SessionFact(1, A, Kst.startOf(A), 900, 0, false)));
        when(userService.findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L)))
                .thenReturn(List.of(new UserFact(1, "곰", signup, "CSAT", null)));
        when(roomMetricsService.findJoins(FACT_FROM, FACT_TO))
                .thenReturn(List.of(new UserEventFact(1, Kst.startOf(A.minusDays(2)))));
        when(amplitudeQueryService.findCounts(A.minusDays(61), A, AmplitudeKeys.ALL))
                .thenReturn(List.of(
                        new AmplitudeDailyCount(1, A, "app_launched", 2),
                        new AmplitudeDailyCount(5, A, "app_foregrounded", 1),
                        new AmplitudeDailyCount(5, A.minusDays(1), "app_launched", 1)));
        when(amplitudeQueryService.findAttributes(Set.of(1L)))
                .thenReturn(Map.of(1L, new AmplitudeAttribute("instagram", true, "ios")));
        when(userService.findExistingUserIds(Set.of(1L, 5L))).thenReturn(Set.of(1L, 5L));
        stubEmptyExtras(Set.of(1L));

        ReportData data = loader(Set.of()).load(A);

        assertThat(data.anchor()).isEqualTo(A);
        assertThat(data.amplitudeDates()).containsExactly(A);
        assertThat(data.appOpenedOnAnchor()).containsExactlyInAnyOrder(1L, 5L);
        assertThat(data.users()).singleElement().satisfies(user -> {
            assertThat(user.userId()).isEqualTo(1);
            assertThat(user.studied(A)).isTrue();
            assertThat(user.activityCount(Activity.ROOM_JOIN, A.minusDays(2), A.minusDays(2)))
                    .isEqualTo(1);
            assertThat(user.amplitudeCount("app_launched", A, A)).isEqualTo(2);
            assertThat(user.attribute()).contains(new AmplitudeAttribute("instagram", true, "ios"));
        });
    }

    @Test
    void 제외_유저는_세션_유저_앱_연_사람_어디에도_남지_않는다() {
        Instant signup = Kst.startOf(A.minusDays(1));
        when(sessionMetricsService.findSessionFacts(A.minusDays(61), A.plusDays(1)))
                .thenReturn(List.of(
                        new SessionFact(1, A, Kst.startOf(A), 900, 0, false),
                        new SessionFact(7, A, Kst.startOf(A), 900, 0, false)));
        when(userService.findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L)))
                .thenReturn(
                        List.of(new UserFact(1, "곰", signup, null, null), new UserFact(7, "운영자", signup, null, null)));
        when(roomMetricsService.findJoins(FACT_FROM, FACT_TO)).thenReturn(List.of());
        when(amplitudeQueryService.findCounts(any(), any(), any()))
                .thenReturn(List.of(new AmplitudeDailyCount(7, A, "app_launched", 1)));
        when(amplitudeQueryService.findAttributes(Set.of(1L))).thenReturn(Map.of());
        stubEmptyExtras(Set.of(1L));

        ReportData data = loader(Set.of(7L)).load(A);

        assertThat(data.users()).extracting(UserTimeline::userId).containsExactly(1L);
        assertThat(data.appOpenedOnAnchor()).isEmpty();
        verify(userService).findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L));
    }

    @Test
    void 앱을_연_사람은_users에_있는_회원만_센다() {
        when(sessionMetricsService.findSessionFacts(A.minusDays(61), A.plusDays(1)))
                .thenReturn(List.of());
        when(userService.findReportUsers(Kst.startOf(A.minusDays(68)), Set.of()))
                .thenReturn(List.of());
        when(roomMetricsService.findJoins(FACT_FROM, FACT_TO)).thenReturn(List.of());
        when(amplitudeQueryService.findCounts(any(), any(), any()))
                .thenReturn(List.of(
                        new AmplitudeDailyCount(5, A, "app_launched", 1),
                        new AmplitudeDailyCount(99, A, "app_launched", 1))); // Amplitude에만 있는 id
        when(amplitudeQueryService.findAttributes(Set.of())).thenReturn(Map.of());
        when(userService.findExistingUserIds(Set.of(5L, 99L))).thenReturn(Set.of(5L));
        stubEmptyExtras(Set.of());

        ReportData data = loader(Set.of()).load(A);

        assertThat(data.appOpenedOnAnchor()).containsExactly(5L);
    }
}
