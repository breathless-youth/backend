package project.study.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.mission.controller.HomeMissionController;
import project.study.mission.dto.HomeMissionResponse;
import project.study.mission.service.HomeMissionRewardService;
import project.study.mission.service.HomeMissionService;
import project.study.point.dto.PointReason;
import project.study.point.service.PointService;
import project.study.point.service.PointStart;

class HomeMissionRewardDeliveryTest {
    private static final long USER = 7L;
    private static final PointReason REASON = PointReason.MISSION;
    private final HomeMissionService missions = mock(HomeMissionService.class);
    private final PointService points = mock(PointService.class);
    private final PointStart pointStart = mock(PointStart.class);
    private final HomeMissionController controller = new HomeMissionController(
            missions, new HomeMissionRewardService(missions, points, pointStart, 50, 100, 300));

    @Test
    void 동시_요청으로_세_번째_단계가_완료돼도_두_번째_응답에는_그_보상을_싣지_않는다() {
        when(missions.complete(USER, 2, true)).thenReturn(mission(2, 3));
        when(missions.completedSteps(USER, true)).thenReturn(3);
        when(pointStart.started(USER)).thenReturn(true);
        when(points.earn(USER, REASON, "home-v3:2", 100, "home-v3/2", null)).thenReturn(true);

        assertThat(controller.complete(USER, 2, true).rewardedPoints()).isEqualTo(100);
        verify(points, never()).earn(USER, REASON, "home-v3:3", 300, "home-v3/3", null);
    }

    @Test
    void 완료_후_지급이_실패하면_아직_받을_수_있는_보상을_알린다() {
        when(missions.complete(USER, 3, true)).thenReturn(mission(3, null));
        when(missions.completedSteps(USER, true)).thenReturn(3);
        when(pointStart.started(USER)).thenReturn(true);
        when(points.granted(USER, REASON, "home-v3:1")).thenReturn(true);
        when(points.granted(USER, REASON, "home-v3:2")).thenReturn(true);
        when(points.earn(USER, REASON, "home-v3:3", 300, "home-v3/3", null))
                .thenThrow(new IllegalStateException("temporary payout failure"));

        HomeMissionResponse result = controller.complete(USER, 3, true);

        assertThat(result.completedSteps()).isEqualTo(3);
        assertThat(result.rewardedPoints()).isZero();
        assertThat(result.claimablePoints()).isEqualTo(300);
    }

    @Test
    void 지급에_성공하면_같은_단계의_보상을_조회하지_않고_받은_금액을_응답한다() {
        when(missions.complete(USER, 3, true)).thenReturn(mission(3, null));
        when(missions.completedSteps(USER, true)).thenReturn(3);
        when(pointStart.started(USER)).thenReturn(true);
        when(points.earn(USER, REASON, "home-v3:3", 300, "home-v3/3", null)).thenReturn(true);

        HomeMissionResponse result = controller.complete(USER, 3, true);

        assertThat(result.rewardedPoints()).isEqualTo(300);
        assertThat(result.claimablePoints()).isZero();
        verify(points, never()).granted(USER, REASON, "home-v3:3");
    }

    private static HomeMissionResponse mission(int completed, Integer current) {
        return new HomeMissionResponse(
                "home-v3", LocalDate.of(2026, 11, 7), completed, current, false, 300, 0, 0, List.of());
    }
}
